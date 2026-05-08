package com.vdzon.newsfeedbackend.rss.infrastructure

import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.vdzon.newsfeedbackend.rss.RssItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

@Component
class RssFetcher {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()

    fun fetch(feedUrl: String): List<RssItem> {
        return try {
            val req = HttpRequest.newBuilder().uri(URI.create(feedUrl))
                .header("User-Agent", "PersonalNewsFeed/1.0")
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() >= 400) {
                log.warn("[RSS] {} -> {}", feedUrl, resp.statusCode())
                return emptyList()
            }
            val feed: SyndFeed = SyndFeedInput().build(XmlReader(resp.body()))
            feed.entries.mapNotNull { entry ->
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
        } catch (e: Exception) {
            log.warn("[RSS] failed to fetch {}: {}", feedUrl, e.message)
            emptyList()
        }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&[a-zA-Z]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
