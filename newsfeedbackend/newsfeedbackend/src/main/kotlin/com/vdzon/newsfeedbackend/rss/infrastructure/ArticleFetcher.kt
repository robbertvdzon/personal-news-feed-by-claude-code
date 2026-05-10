package com.vdzon.newsfeedbackend.rss.infrastructure

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Fetches full article HTML and produces a plain-text body that AI can
 * use as richer context than just the RSS snippet (which is capped at
 * 1000 chars and often only the lead paragraph).
 *
 * Best-effort: returns null on timeout / error / non-HTML response.
 * No JS rendering, no readability heuristics — strips tags and trims to
 * a sane size, which is good enough to ground a 400-600 word summary.
 */
@Component
class ArticleFetcher(
    private val callLogger: ExternalCallLogger
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    fun fetchPlainText(username: String, url: String, maxChars: Int = 8000): String? {
        val started = Instant.now()
        var status = "ok"
        var errorMessage: String? = null
        var charsKept = 0L
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 PersonalNewsFeed/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(20))
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() >= 400) {
                log.debug("[ArticleFetcher] {} -> {}", url, resp.statusCode())
                status = "error"
                errorMessage = "http ${resp.statusCode()}"
                null
            } else {
                val text = stripHtml(resp.body()).take(maxChars)
                charsKept = text.length.toLong()
                text
            }
        } catch (e: Exception) {
            log.debug("[ArticleFetcher] failed {}: {}", url, e.message)
            status = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            null
        }.also {
            logFetch(username, url, started, charsKept, status, errorMessage)
        }
    }

    private fun logFetch(
        username: String,
        url: String,
        started: Instant,
        charsKept: Long,
        status: String,
        errorMessage: String?
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_WEB,
                    action = ExternalCall.ACTION_ARTICLE_FETCH,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = charsKept,
                    unitType = ExternalCall.UNIT_CHARACTERS,
                    costUsd = 0.0,
                    status = status,
                    errorMessage = errorMessage,
                    subject = url.take(120)
                )
            )
        } catch (e: Exception) {
            log.warn("[ArticleFetcher] could not log external_call: {}", e.message)
        }
    }

    private fun stripHtml(html: String): String {
        // Drop everything inside <script> and <style> blocks first.
        val cleaned = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<nav\\b[^>]*>.*?</nav>"), " ")
            .replace(Regex("(?is)<header\\b[^>]*>.*?</header>"), " ")
            .replace(Regex("(?is)<footer\\b[^>]*>.*?</footer>"), " ")
        // Tag strip + entity decode + whitespace collapse.
        return cleaned
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("&[a-zA-Z]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
