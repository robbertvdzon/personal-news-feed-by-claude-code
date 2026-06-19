package com.vdzon.newsfeedbackend.ai.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiPricingProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.ai.OpenAiChatResponse
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
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
 * KAN-63: thin HTTP-wrapper voor OpenAI's `/v1/chat/completions`.
 *
 * SF-114: uitgebreid van "alleen vertaling, vast model" naar een algemene
 * client. Alles loopt via [doComplete]; bovenop staan drie overloads:
 *  - [complete] zonder model → vertaal-flow met het vaste translate-model.
 *  - [complete] met model → vrije chat-completion met een geconfigureerd model.
 *  - [completeJson] → idem, maar met OpenAI Structured Outputs (`strict:true`)
 *    zodat de respons strikt aan een JSON-schema voldoet.
 *
 * Eén-shot call, geen retries — de caller vangt fouten op. Elke call wordt
 * gelogd via [ExternalCallLogger] zodat ze in het kosten-dashboard verschijnen.
 */
@Component
class OpenAiChatHttpClient(
    @Value("\${app.openai.api-key:}") private val apiKey: String,
    @Value("\${app.openai.base-url:https://api.openai.com}") private val baseUrl: String,
    @Value("\${app.openai.translate-model:gpt-4o-mini}") private val translateModel: String,
    private val pricing: AiPricingProperties,
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
    ): OpenAiChatResponse =
        doComplete(
            model = translateModel,
            action = action,
            username = username,
            system = system,
            user = user,
            subject = subject,
            maxOutputTokens = maxOutputTokens,
            responseFormat = null,
            costFn = { i, o -> pricing.tokenCost(translateModel, i, o) }
        )

    override fun complete(
        model: String,
        action: String,
        username: String,
        system: String,
        user: String,
        subject: String?,
        maxOutputTokens: Int
    ): OpenAiChatResponse =
        doComplete(
            model = model,
            action = action,
            username = username,
            system = system,
            user = user,
            subject = subject,
            maxOutputTokens = maxOutputTokens,
            responseFormat = null,
            costFn = { i, o -> pricing.tokenCost(model, i, o) }
        )

    override fun completeJson(
        model: String,
        schemaName: String,
        schema: String,
        action: String,
        username: String,
        system: String,
        user: String,
        subject: String?,
        maxOutputTokens: Int
    ): OpenAiChatResponse {
        // Structured Outputs: response_format = json_schema, strict:true. Het
        // schema staat — net als de system-prompt — vooraan in de body, wat
        // OpenAI's prompt-caching ten goede komt (statisch deel eerst).
        val responseFormat = mapOf(
            "type" to "json_schema",
            "json_schema" to mapOf(
                "name" to schemaName,
                "strict" to true,
                "schema" to mapper.readTree(schema)
            )
        )
        return doComplete(
            model = model,
            action = action,
            username = username,
            system = system,
            user = user,
            subject = subject,
            maxOutputTokens = maxOutputTokens,
            responseFormat = responseFormat,
            costFn = { i, o -> pricing.tokenCost(model, i, o) }
        )
    }

    private fun doComplete(
        model: String,
        action: String,
        username: String,
        system: String,
        user: String,
        subject: String?,
        maxOutputTokens: Int,
        responseFormat: Map<String, Any>?,
        costFn: (Long, Long) -> Double
    ): OpenAiChatResponse {
        val started = Instant.now()
        if (apiKey.isBlank()) {
            log.warn("[OpenAI-chat] no API key configured — returning empty response for action '{}'", action)
            logCall(action, username, model, started, 0, 0, 0.0, subject,
                status = "error", errorMessage = "no API key configured")
            return OpenAiChatResponse("", 0, 0, 0.0, model, "error", "no API key configured")
        }
        val payload = linkedMapOf<String, Any>(
            "model" to model,
            // SF-115: gpt-5.x (en andere recente modellen) verwachten
            // `max_completion_tokens`; het oudere `max_tokens` wordt door die
            // modellen geweigerd. `max_completion_tokens` werkt ook voor de
            // legacy gpt-4o-mini-vertaalflow, dus universeel veilig.
            "max_completion_tokens" to maxOutputTokens,
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user)
            )
        )
        if (responseFormat != null) payload["response_format"] = responseFormat
        val body = mapper.writeValueAsString(payload)
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
                logCall(action, username, model, started, 0, 0, 0.0, subject,
                    status = "error", errorMessage = "http $code: ${errBody.take(120)}")
                return OpenAiChatResponse("", 0, 0, 0.0, model, "error", "HTTP $code: ${errBody.take(160)}")
            }
            val tree = mapper.readTree(resp.body())
            val text = tree.path("choices").path(0).path("message").path("content").asText("")
            val usage = tree.path("usage")
            val inputTokens = usage.path("prompt_tokens").asInt(0)
            val outputTokens = usage.path("completion_tokens").asInt(0)
            val cost = costFn(inputTokens.toLong(), outputTokens.toLong())
            logCall(action, username, model, started,
                inputTokens.toLong(), outputTokens.toLong(), cost, subject,
                status = "ok", errorMessage = null)
            log.info("[OpenAI-chat] {} ok model={} in={} out={} cost=${'$'}{}",
                action, model, inputTokens, outputTokens, "%.4f".format(cost))
            OpenAiChatResponse(text, inputTokens, outputTokens, cost, model, "ok", null)
        } catch (e: Exception) {
            log.warn("[OpenAI-chat] {} failed: {}", action, e.message)
            logCall(action, username, model, started, 0, 0, 0.0, subject,
                status = "error", errorMessage = e.message ?: e.javaClass.simpleName)
            OpenAiChatResponse("", 0, 0, 0.0, model, "error", e.message ?: e.javaClass.simpleName)
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
