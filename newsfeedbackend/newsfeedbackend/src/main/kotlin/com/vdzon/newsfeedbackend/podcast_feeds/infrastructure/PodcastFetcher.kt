package com.vdzon.newsfeedbackend.podcast_feeds.infrastructure

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.podcast_feeds.PodcastEpisode
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
class PodcastFetcher(
    private val callLogger: ExternalCallLogger
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    fun fetch(feedUrl: String, username: String = "system"): List<PodcastEpisode> {
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
                log.warn("[PODCAST] {} -> {}", feedUrl, resp.statusCode())
                status = "error"
                errorMessage = "http ${resp.statusCode()}"
                return emptyList()
            }
            val feed: SyndFeed = SyndFeedInput().build(XmlReader(resp.body()))
            val episodes = feed.entries.mapNotNull { entry ->
                // Zoek de podcast-enclosure (audio-bestand) in de entry
                val podcastUrl = entry.enclosures
                    .filter { it.type?.contains("audio") == true }
                    .firstOrNull()?.url
                    ?: return@mapNotNull null

                val pubDate = entry.publishedDate?.toInstant() ?: entry.updatedDate?.toInstant()
                val publishedDay = pubDate?.atZone(ZoneOffset.UTC)?.toLocalDate()
                    ?.format(DateTimeFormatter.ISO_LOCAL_DATE)

                // Alleen episodes uit de afgelopen 14 dagen
                if (pubDate != null && pubDate.isBefore(Instant.now().minus(14, ChronoUnit.DAYS))) {
                    return@mapNotNull null
                }

                val guid = entry.uri ?: entry.title ?: return@mapNotNull null
                val description = stripHtml(entry.description?.value.orEmpty())

                PodcastEpisode(
                    guid = guid,
                    feedUrl = feedUrl,
                    title = entry.title.orEmpty(),
                    description = description,
                    status = "PENDING",
                    podcastUrl = podcastUrl,
                    showNotes = description.take(1000)
                )
            }
            itemCount = episodes.size
            return episodes
        } catch (e: Exception) {
            log.warn("[PODCAST] failed to fetch {}: {}", feedUrl, e.message)
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
                    provider = "PODCAST_RSS",
                    action = "podcast_fetch",
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
            log.warn("[PODCAST] could not log external_call: {}", e.message)
        }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&[a-zA-Z]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
