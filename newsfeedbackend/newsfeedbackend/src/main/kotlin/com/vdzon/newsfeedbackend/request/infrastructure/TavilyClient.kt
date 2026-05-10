package com.vdzon.newsfeedbackend.request.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
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

data class TavilyResult(val title: String, val url: String, val snippet: String, val publishedDate: String? = null)

@Component
class TavilyClient(
    @Value("\${app.tavily.api-key:}") private val apiKey: String,
    @Value("\${app.tavily.base-url:https://api.tavily.com}") private val baseUrl: String,
    private val mapper: ObjectMapper,
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    fun search(username: String, query: String, days: Int = 3, maxResults: Int = 12): List<TavilyResult> {
        if (apiKey.isBlank()) {
            log.warn("[Tavily] no API key configured")
            logCall(ExternalCall.ACTION_TAVILY_SEARCH, username, query, Instant.now(), 0.0, "error", "no API key")
            return emptyList()
        }
        val started = Instant.now()
        return try {
            val body = mapper.writeValueAsString(
                mapOf(
                    "api_key" to apiKey,
                    "query" to query,
                    "days" to days,
                    "max_results" to maxResults,
                    "search_depth" to "basic",
                    "include_answer" to false
                )
            )
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/search"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(1))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() >= 400) {
                log.warn("[Tavily] {} -> {}", query, resp.statusCode())
                logCall(ExternalCall.ACTION_TAVILY_SEARCH, username, query, started, 0.0, "error",
                    "http ${resp.statusCode()}")
                return emptyList()
            }
            val tree = mapper.readTree(resp.body())
            val results = tree.path("results").mapNotNull { node ->
                val url = node.path("url").asText(null) ?: return@mapNotNull null
                TavilyResult(
                    title = node.path("title").asText(""),
                    url = url,
                    snippet = node.path("content").asText(""),
                    publishedDate = node.path("published_date").asText(null)
                )
            }
            logCall(ExternalCall.ACTION_TAVILY_SEARCH, username, query, started,
                Pricing.tavilySearchCost(), "ok", null)
            results
        } catch (e: Exception) {
            log.warn("[Tavily] search failed: {}", e.message)
            logCall(ExternalCall.ACTION_TAVILY_SEARCH, username, query, started, 0.0, "error", e.message)
            emptyList()
        }
    }

    fun extract(username: String, urls: List<String>): Map<String, String> {
        if (apiKey.isBlank() || urls.isEmpty()) return emptyMap()
        val started = Instant.now()
        val subj = "extract ${urls.size} urls"
        return try {
            val body = mapper.writeValueAsString(
                mapOf("api_key" to apiKey, "urls" to urls)
            )
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/extract"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() >= 400) {
                log.warn("[Tavily] extract -> {}", resp.statusCode())
                logCall(ExternalCall.ACTION_TAVILY_EXTRACT, username, subj, started, 0.0, "error",
                    "http ${resp.statusCode()}")
                return emptyMap()
            }
            val tree = mapper.readTree(resp.body())
            val out = tree.path("results").associate { node ->
                val url = node.path("url").asText("")
                val text = node.path("raw_content").asText("").take(8000)
                url to text
            }
            logCall(ExternalCall.ACTION_TAVILY_EXTRACT, username, subj, started,
                Pricing.tavilyExtractCost(), "ok", null)
            out
        } catch (e: Exception) {
            log.warn("[Tavily] extract failed: {}", e.message)
            logCall(ExternalCall.ACTION_TAVILY_EXTRACT, username, subj, started, 0.0, "error", e.message)
            emptyMap()
        }
    }

    private fun logCall(
        action: String,
        username: String,
        subject: String,
        started: Instant,
        cost: Double,
        status: String,
        errorMessage: String?
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_TAVILY,
                    action = action,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = 1,
                    unitType = ExternalCall.UNIT_QUERIES,
                    costUsd = cost,
                    status = status,
                    errorMessage = errorMessage,
                    subject = subject.take(120)
                )
            )
        } catch (e: Exception) {
            log.warn("[Tavily] could not log external_call: {}", e.message)
        }
    }
}
