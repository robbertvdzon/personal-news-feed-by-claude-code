package com.vdzon.newsfeedbackend.search

import tools.jackson.databind.ObjectMapper
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

data class TavilyResult(val title: String, val url: String, val snippet: String, val publishedDate: String? = null)

@Component
class TavilyClient(
    @param:Value("\${app.tavily.api-key:}") private val apiKey: String,
    @param:Value("\${app.tavily.base-url:https://api.tavily.com}") private val baseUrl: String,
    private val mapper: ObjectMapper,
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    fun search(username: String, query: String, days: Int = 3, maxResults: Int = 12): List<TavilyResult> {
        val subj = query.take(120)
        if (apiKey.isBlank()) {
            log.warn("[Tavily] no API key configured")
            callLogger.logCall(
                ExternalCall.PROVIDER_TAVILY, ExternalCall.ACTION_TAVILY_SEARCH, username, Instant.now(),
                ExternalCall.UNIT_QUERIES, "error",
                units = 1, costUsd = 0.0, errorMessage = "no API key", subject = subj
            )
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
                callLogger.logCall(
                    ExternalCall.PROVIDER_TAVILY, ExternalCall.ACTION_TAVILY_SEARCH, username, started,
                    ExternalCall.UNIT_QUERIES, "error",
                    units = 1, costUsd = 0.0, errorMessage = "http ${resp.statusCode()}", subject = subj
                )
                return emptyList()
            }
            val tree = mapper.readTree(resp.body())
            val results = tree.path("results").mapNotNull { node ->
                val url = node.path("url").asString(null) ?: return@mapNotNull null
                TavilyResult(
                    title = node.path("title").asString(""),
                    url = url,
                    snippet = node.path("content").asString(""),
                    publishedDate = node.path("published_date").asString(null)
                )
            }
            callLogger.logCall(
                ExternalCall.PROVIDER_TAVILY, ExternalCall.ACTION_TAVILY_SEARCH, username, started,
                ExternalCall.UNIT_QUERIES, "ok",
                units = 1, costUsd = Pricing.tavilySearchCost(), errorMessage = null, subject = subj
            )
            results
        } catch (e: Exception) {
            log.warn("[Tavily] search failed: {}", e.message)
            callLogger.logCall(
                ExternalCall.PROVIDER_TAVILY, ExternalCall.ACTION_TAVILY_SEARCH, username, started,
                ExternalCall.UNIT_QUERIES, "error",
                units = 1, costUsd = 0.0, errorMessage = e.message, subject = subj
            )
            emptyList()
        }
    }

    fun extract(username: String, urls: List<String>): Map<String, String> {
        if (apiKey.isBlank() || urls.isEmpty()) return emptyMap()
        val started = Instant.now()
        val subj = "extract ${urls.size} urls".take(120)
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
                callLogger.logCall(
                    ExternalCall.PROVIDER_TAVILY, ExternalCall.ACTION_TAVILY_EXTRACT, username, started,
                    ExternalCall.UNIT_QUERIES, "error",
                    units = 1, costUsd = 0.0, errorMessage = "http ${resp.statusCode()}", subject = subj
                )
                return emptyMap()
            }
            val tree = mapper.readTree(resp.body())
            val out = tree.path("results").associate { node ->
                val url = node.path("url").asString("")
                val text = node.path("raw_content").asString("").take(8000)
                url to text
            }
            callLogger.logCall(
                ExternalCall.PROVIDER_TAVILY, ExternalCall.ACTION_TAVILY_EXTRACT, username, started,
                ExternalCall.UNIT_QUERIES, "ok",
                units = 1, costUsd = Pricing.tavilyExtractCost(), errorMessage = null, subject = subj
            )
            out
        } catch (e: Exception) {
            log.warn("[Tavily] extract failed: {}", e.message)
            callLogger.logCall(
                ExternalCall.PROVIDER_TAVILY, ExternalCall.ACTION_TAVILY_EXTRACT, username, started,
                ExternalCall.UNIT_QUERIES, "error",
                units = 1, costUsd = 0.0, errorMessage = e.message, subject = subj
            )
            emptyMap()
        }
    }
}
