package com.vdzon.newsfeedbackend.ai.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiResponse
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.external_call.Pricing
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore

@Component
class AnthropicHttpClient(
    @Value("\${app.anthropic.api-key:}") private val apiKey: String,
    @Value("\${app.anthropic.base-url:https://api.anthropic.com}") private val baseUrl: String,
    @Value("\${app.anthropic.model:claude-sonnet-4-5}") private val mainModel: String,
    @Value("\${app.anthropic.summary-model:claude-haiku-4-5-20251001}") private val summaryModel: String,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry,
    private val callLogger: ExternalCallLogger
) : AnthropicClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val semaphore = Semaphore(3)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override fun summaryModel(): String = summaryModel
    override fun mainModel(): String = mainModel

    override fun complete(
        operation: String,
        action: String,
        username: String,
        system: String,
        user: String,
        model: String?,
        maxTokens: Int,
        subject: String?
    ): AiResponse {
        val chosen = model ?: mainModel
        if (apiKey.isBlank()) {
            log.warn("[Anthropic] no API key configured — returning empty response for '{}'", operation)
            logCall(action, username, chosen, Instant.now(), 0, 0, 0.0, subject,
                status = "error", errorMessage = "no API key configured")
            return AiResponse("", 0, 0, 0.0, chosen)
        }

        var attempt = 0
        var delayMs = 15_000L
        var lastError: Exception? = null
        while (attempt < 4) {
            val started = Instant.now()
            try {
                semaphore.acquire()
                try {
                    val startedMs = System.currentTimeMillis()
                    val body = mapper.writeValueAsString(
                        mapOf(
                            "model" to chosen,
                            "max_tokens" to maxTokens,
                            "system" to system,
                            "messages" to listOf(mapOf("role" to "user", "content" to user))
                        )
                    )
                    val req = HttpRequest.newBuilder()
                        .uri(URI.create("$baseUrl/v1/messages"))
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .header("content-type", "application/json")
                        .timeout(Duration.ofMinutes(2))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                    val took = System.currentTimeMillis() - startedMs
                    if (resp.statusCode() == 429) {
                        meters.counter("newsfeed.ai.retries", "operation", operation).increment()
                        throw RateLimitException("rate limited")
                    }
                    if (resp.statusCode() == 529) {
                        meters.counter("newsfeed.ai.retries", "operation", operation).increment()
                        throw RateLimitException("overloaded")
                    }
                    if (resp.statusCode() >= 400) {
                        val errBody = resp.body()
                        val errSummary = when {
                            errBody.contains("credit balance is too low", ignoreCase = true) -> {
                                log.error(
                                    "[Anthropic] {} → 400 API-tegoed is op. Vul credits aan op " +
                                        "https://console.anthropic.com/settings/billing.", operation
                                )
                                "credit balance too low"
                            }
                            resp.statusCode() == 401 -> {
                                log.error("[Anthropic] {} → 401 ongeldige API-key.", operation)
                                "invalid API key"
                            }
                            resp.statusCode() == 403 -> {
                                log.error("[Anthropic] {} → 403 toegang geweigerd: {}", operation, errBody.take(200))
                                "forbidden"
                            }
                            else -> {
                                log.error("[Anthropic] {} → {} {}", operation, resp.statusCode(), errBody.take(500))
                                "http ${resp.statusCode()}"
                            }
                        }
                        logCall(action, username, chosen, started, 0, 0, 0.0, subject,
                            status = "error", errorMessage = errSummary)
                        return AiResponse("", 0, 0, 0.0, chosen)
                    }
                    val parsed = mapper.readTree(resp.body())
                    val text = parsed.path("content")
                        .find { it.path("type").asText() == "text" }
                        ?.path("text")?.asText() ?: ""
                    val usage = parsed.path("usage")
                    val inT = usage.path("input_tokens").asInt(0)
                    val outT = usage.path("output_tokens").asInt(0)
                    val cost = Pricing.anthropicCost(chosen, inT.toLong(), outT.toLong())
                    meters.counter("newsfeed.ai.calls.total", "operation", operation, "model", chosen).increment()
                    meters.timer("newsfeed.ai.calls.duration", "operation", operation, "model", chosen)
                        .record(Duration.ofMillis(took))
                    meters.summary("newsfeed.ai.cost.usd", "operation", operation).record(cost)
                    log.debug("[Anthropic] '{}' model={} took={}ms in={} out={} cost={}", operation, chosen, took, inT, outT, cost)
                    logCall(action, username, chosen, started, inT.toLong(), outT.toLong(), cost, subject,
                        status = "ok", errorMessage = null)
                    return AiResponse(text, inT, outT, cost, chosen)
                } finally {
                    semaphore.release()
                }
            } catch (e: RateLimitException) {
                lastError = e
                Thread.sleep(delayMs)
                delayMs *= 2
                attempt++
            } catch (e: Exception) {
                lastError = e
                log.warn("[Anthropic] {} attempt {} failed: {}", operation, attempt, e.message)
                Thread.sleep(delayMs)
                delayMs *= 2
                attempt++
            }
        }
        log.error("[Anthropic] {} failed after {} attempts", operation, attempt, lastError)
        logCall(action, username, chosen, Instant.now(), 0, 0, 0.0, subject,
            status = "error", errorMessage = lastError?.message ?: "failed after $attempt attempts")
        return AiResponse("", 0, 0, 0.0, chosen)
    }

    private fun logCall(
        action: String,
        username: String,
        model: String,
        started: Instant,
        tokensIn: Long,
        tokensOut: Long,
        cost: Double,
        subject: String?,
        status: String,
        errorMessage: String?
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_ANTHROPIC,
                    action = action,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    tokensIn = tokensIn,
                    tokensOut = tokensOut,
                    units = tokensIn + tokensOut,
                    unitType = ExternalCall.UNIT_TOKENS,
                    costUsd = cost,
                    status = status,
                    errorMessage = errorMessage,
                    subject = subject?.take(120) ?: "model=$model"
                )
            )
        } catch (e: Exception) {
            log.warn("[Anthropic] could not log external_call: {}", e.message)
        }
    }

    private class RateLimitException(msg: String) : RuntimeException(msg)
}
