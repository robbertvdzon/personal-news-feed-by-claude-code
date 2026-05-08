package com.vdzon.newsfeedbackend.ai.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiResponse
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Semaphore

@Component
class AnthropicHttpClient(
    @Value("\${app.anthropic.api-key:}") private val apiKey: String,
    @Value("\${app.anthropic.base-url:https://api.anthropic.com}") private val baseUrl: String,
    @Value("\${app.anthropic.model:claude-sonnet-4-5}") private val mainModel: String,
    @Value("\${app.anthropic.summary-model:claude-haiku-4-5-20251001}") private val summaryModel: String,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry
) : AnthropicClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val semaphore = Semaphore(3)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override fun summaryModel(): String = summaryModel
    override fun mainModel(): String = mainModel

    override fun complete(operation: String, system: String, user: String, model: String?, maxTokens: Int): AiResponse {
        if (apiKey.isBlank()) {
            log.warn("[Anthropic] no API key configured — returning empty response for '{}'", operation)
            return AiResponse("", 0, 0, 0.0, model ?: mainModel)
        }
        val chosen = model ?: mainModel

        var attempt = 0
        var delayMs = 15_000L
        var lastError: Exception? = null
        while (attempt < 4) {
            try {
                semaphore.acquire()
                try {
                    val started = System.currentTimeMillis()
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
                    val took = System.currentTimeMillis() - started
                    if (resp.statusCode() == 429) {
                        meters.counter("newsfeed.ai.retries", "operation", operation).increment()
                        throw RateLimitException("rate limited")
                    }
                    if (resp.statusCode() == 529) {
                        // Anthropic-specifiek: tijdelijke overbelasting van het systeem.
                        meters.counter("newsfeed.ai.retries", "operation", operation).increment()
                        throw RateLimitException("overloaded")
                    }
                    if (resp.statusCode() >= 400) {
                        val errBody = resp.body()
                        when {
                            errBody.contains("credit balance is too low", ignoreCase = true) ->
                                log.error(
                                    "[Anthropic] {} → 400 API-tegoed is op. Dit gaat NIET vanzelf over. " +
                                        "Vul credits aan op https://console.anthropic.com/settings/billing. " +
                                        "Let op: een Pro-abonnement op claude.ai geldt NIET voor API-gebruik — " +
                                        "die billing is volledig gescheiden.", operation
                                )
                            resp.statusCode() == 401 ->
                                log.error(
                                    "[Anthropic] {} → 401 ongeldige API-key. Check ANTHROPIC_API_KEY in je env.",
                                    operation
                                )
                            resp.statusCode() == 403 ->
                                log.error(
                                    "[Anthropic] {} → 403 toegang geweigerd: {}",
                                    operation, errBody.take(200)
                                )
                            else ->
                                log.error("[Anthropic] {} → {} {}", operation, resp.statusCode(), errBody.take(500))
                        }
                        return AiResponse("", 0, 0, 0.0, chosen)
                    }
                    val parsed = mapper.readTree(resp.body())
                    val text = parsed.path("content")
                        .find { it.path("type").asText() == "text" }
                        ?.path("text")?.asText() ?: ""
                    val usage = parsed.path("usage")
                    val inT = usage.path("input_tokens").asInt(0)
                    val outT = usage.path("output_tokens").asInt(0)
                    val cost = estimateCost(chosen, inT, outT)
                    meters.counter("newsfeed.ai.calls.total", "operation", operation, "model", chosen).increment()
                    meters.timer("newsfeed.ai.calls.duration", "operation", operation, "model", chosen)
                        .record(Duration.ofMillis(took))
                    meters.summary("newsfeed.ai.cost.usd", "operation", operation).record(cost)
                    log.debug("[Anthropic] '{}' model={} took={}ms in={} out={} cost={}", operation, chosen, took, inT, outT, cost)
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
        return AiResponse("", 0, 0, 0.0, chosen)
    }

    private fun estimateCost(model: String, inputTokens: Int, outputTokens: Int): Double {
        val (inPrice, outPrice) = when {
            model.contains("haiku") -> 1.0 to 5.0
            model.contains("opus") -> 15.0 to 75.0
            else -> 3.0 to 15.0
        }
        return (inputTokens / 1_000_000.0) * inPrice + (outputTokens / 1_000_000.0) * outPrice
    }

    private class RateLimitException(msg: String) : RuntimeException(msg)
}
