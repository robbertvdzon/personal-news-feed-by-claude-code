package com.vdzon.newsfeedbackend.rss.infrastructure

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.rss.RssItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

@Component
class RssFetcher(
    private val callLogger: ExternalCallLogger
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    /**
     * Haalt één RSS-feed op en parsed naar [RssItem]s. Logt elke fetch
     * (ook fouten) als `rss_fetch` in `external_calls.jsonl` met
     * `units = #items` — `costUsd` blijft 0 omdat RSS gratis is, maar de
     * regel is wel handig voor "is mijn feed wel echt opgehaald?"-debug.
     *
     * `username` is optioneel zodat de scheduler hem voor "system" kan
     * loggen wanneer er geen request-context is.
     */
    fun fetch(feedUrl: String, username: String = "system"): List<RssItem> {
        val started = Instant.now()
        var status = "ok"
        var errorMessage: String? = null
        var itemCount = 0
        try {
            val req = HttpRequest.newBuilder().uri(URI.create(feedUrl))
                .header("User-Agent", "PersonalNewsFeed/1.0")
                .timeout(java.time.Duration.ofSeconds(20))
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() >= 400) {
                log.warn("[RSS] {} -> {}", feedUrl, resp.statusCode())
                status = "error"
                errorMessage = "http ${resp.statusCode()}"
                return emptyList()
            }
            val feed: SyndFeed = SyndFeedInput().build(XmlReader(resp.body()))
            val items = feed.entries.mapNotNull { entry ->
                val url = entry.link ?: return@mapNotNull null
                val pubDate = entry.publishedDate?.toInstant() ?: entry.updatedDate?.toInstant()
                val publishedDay = pubDate?.atZone(ZoneOffset.UTC)?.toLocalDate()
                    ?.format(DateTimeFormatter.ISO_LOCAL_DATE)
                if (pubDate != null && pubDate.isBefore(Instant.now().minus(4, ChronoUnit.DAYS))) return@mapNotNull null
                RssItem(
                    id = UUID.randomUUID().toString(),
                    title = entry.title.orEmpty(),
                    snippet = stripHtml(entry.description?.value.orEmpty()).take(1000),
                    url = url,
                    feedUrl = feedUrl,
                    source = feed.title.orEmpty(),
                    publishedDate = publishedDay,
                    timestamp = Instant.now()
                )
            }
            itemCount = items.size
            return items
        } catch (e: Exception) {
            log.warn("[RSS] failed to fetch {}: {}", feedUrl, e.message)
            status = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            return emptyList()
        } finally {
            logFetch(username, feedUrl, started, itemCount, status, errorMessage)
        }
    }

    private fun logFetch(
        username: String,
        feedUrl: String,
        started: Instant,
        itemCount: Int,
        status: String,
        errorMessage: String?
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_RSS,
                    action = ExternalCall.ACTION_RSS_FETCH,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = itemCount.toLong(),
                    unitType = ExternalCall.UNIT_ITEMS,
                    costUsd = 0.0,
                    status = status,
                    errorMessage = errorMessage,
                    subject = feedUrl.take(120)
                )
            )
        } catch (e: Exception) {
            log.warn("[RSS] could not log external_call: {}", e.message)
        }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&[a-zA-Z]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
