package com.vdzon.newsfeedbackend.ai.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.ai.OpenAiChatResponse
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.external_call.Pricing
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

/**
 * KAN-63: thin HTTP-wrapper voor OpenAI's `/v1/chat/completions` endpoint
 * met model `gpt-4o-mini`. Eén-shot call, geen retries — de
 * [com.vdzon.newsfeedbackend.podcast.domain.PodcastTranslator] vangt
 * fouten op en zet de podcast op FAILED met de foutboodschap. Logt elke
 * call via [ExternalCallLogger] zodat ze in het kosten-dashboard
 * verschijnen.
 */
@Component
class OpenAiChatHttpClient(
    @Value("\${app.openai.api-key:}") private val apiKey: String,
    @Value("\${app.openai.base-url:https://api.openai.com}") private val baseUrl: String,
    @Value("\${app.openai.translate-model:gpt-4o-mini}") private val translateModel: String,
    private val mapper: ObjectMapper,
    private val callLogger: ExternalCallLogger
) : OpenAiChatClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override fun translateModel(): String = translateModel

    override fun complete(
        action: String,
        username: String,
        system: String,
        user: String,
        subject: String?,
        maxOutputTokens: Int
    ): OpenAiChatResponse {
        val started = Instant.now()
        if (apiKey.isBlank()) {
            log.warn("[OpenAI-chat] no API key configured — returning empty response for action '{}'", action)
            logCall(action, username, translateModel, started, 0, 0, 0.0, subject,
                status = "error", errorMessage = "no API key configured")
            return OpenAiChatResponse("", 0, 0, 0.0, translateModel, "error", "no API key configured")
        }
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to translateModel,
                "max_tokens" to maxOutputTokens,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user)
                )
            )
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            // 10 min: een volledige podcast-vertaling (KAN-63, ~16k
            // output-tokens op gpt-4o-mini) zit rond de 5 min en tikte
            // soms net over de oude 5-min-grens ("request timed out").
            // Ruime marge; timeout is een bovengrens, geen vaste wachttijd.
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            val code = resp.statusCode()
            if (code >= 400) {
                val errBody = resp.body().take(400)
                log.warn("[OpenAI-chat] {} -> {} body={}", action, code, errBody)
                logCall(action, username, translateModel, started, 0, 0, 0.0, subject,
                    status = "error", errorMessage = "http $code: ${errBody.take(120)}")
                return OpenAiChatResponse("", 0, 0, 0.0, translateModel, "error", "HTTP $code: ${errBody.take(160)}")
            }
            val tree = mapper.readTree(resp.body())
            val text = tree.path("choices").path(0).path("message").path("content").asText("")
            val usage = tree.path("usage")
            val inputTokens = usage.path("prompt_tokens").asInt(0)
            val outputTokens = usage.path("completion_tokens").asInt(0)
            val cost = Pricing.openaiGpt4oMiniCost(inputTokens.toLong(), outputTokens.toLong())
            logCall(action, username, translateModel, started,
                inputTokens.toLong(), outputTokens.toLong(), cost, subject,
                status = "ok", errorMessage = null)
            log.info("[OpenAI-chat] {} ok in={} out={} cost=${'$'}{}",
                action, inputTokens, outputTokens, "%.4f".format(cost))
            OpenAiChatResponse(text, inputTokens, outputTokens, cost, translateModel, "ok", null)
        } catch (e: Exception) {
            log.warn("[OpenAI-chat] {} failed: {}", action, e.message)
            logCall(action, username, translateModel, started, 0, 0, 0.0, subject,
                status = "error", errorMessage = e.message ?: e.javaClass.simpleName)
            OpenAiChatResponse("", 0, 0, 0.0, translateModel, "error", e.message ?: e.javaClass.simpleName)
        }
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
                    provider = ExternalCall.PROVIDER_OPENAI,
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
                    subject = (subject ?: "model=$model").take(120)
                )
            )
        } catch (e: Exception) {
            log.warn("[OpenAI-chat] could not log external_call: {}", e.message)
        }
    }
}
