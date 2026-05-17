package com.vdzon.newsfeedbackend.podcastfeed.infrastructure

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.podcastfeed.EpisodeStatus
import com.vdzon.newsfeedbackend.podcastfeed.PodcastEpisode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Haalt één podcast-RSS op en levert kandidaat-[PodcastEpisode]s in
 * PENDING-status. Géén HTTP-fouten escaleren naar de pipeline — een
 * fout-feed levert simpelweg een lege lijst plus een gelogde
 * `podcast_feed_fetch` met status=error.
 *
 * Voor [validate] wordt dezelfde parser gebruikt maar dan synchroon
 * vanuit de save-endpoint: bij een fout krijgt de UI binnen 10s een
 * leesbare melding terug (AC7).
 */
@Component
class PodcastFeedFetcher(
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    /** Haalt feed-XML op en parsed naar PENDING-episodes. Faalt nooit hard. */
    fun fetch(feedUrl: String, username: String = "system"): List<PodcastEpisode> {
        val started = Instant.now()
        var status = "ok"
        var errorMessage: String? = null
        var itemCount = 0
        try {
            val feed = parse(feedUrl)
            val podcastName = feed.title.orEmpty()
            val episodes = feed.entries.mapNotNull { entry ->
                val enclosure = entry.enclosures?.firstOrNull { it.url != null && it.url.isNotBlank() }
                    ?: return@mapNotNull null
                val guid = (entry.uri?.takeIf { it.isNotBlank() } ?: enclosure.url)
                val pubDate = entry.publishedDate?.toInstant() ?: entry.updatedDate?.toInstant()
                val publishedDay = pubDate?.atZone(ZoneOffset.UTC)?.toLocalDate()
                    ?.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val description = stripHtml(entry.description?.value.orEmpty())
                val durationSeconds = estimateDurationFromEnclosure(enclosure.length)
                PodcastEpisode(
                    guid = guid,
                    feedUrl = feedUrl,
                    title = entry.title.orEmpty(),
                    podcastName = podcastName,
                    audioUrl = enclosure.url,
                    durationSeconds = durationSeconds,
                    description = description,
                    publishedDate = publishedDay,
                    status = EpisodeStatus.PENDING
                )
            }
            itemCount = episodes.size
            return episodes
        } catch (e: Exception) {
            log.warn("[PodcastFeed] failed to fetch {}: {}", feedUrl, e.message)
            status = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            return emptyList()
        } finally {
            logFetch(username, feedUrl, started, itemCount, status, errorMessage)
        }
    }

    /**
     * Synchrone validatie: gooit InvalidPodcastFeedException (via een
     * generieke RuntimeException-message) als de feed niet binnen 5s
     * geparsed kan worden. Geen externe-call-log; dat doet [fetch].
     */
    fun validate(feedUrl: String): Result<String> = try {
        val feed = parse(feedUrl)
        if (feed.entries.isEmpty()) {
            Result.failure(IllegalStateException("Feed bevat geen items"))
        } else if (feed.entries.none { e -> e.enclosures?.any { it.url != null && it.url.isNotBlank() } == true }) {
            Result.failure(IllegalStateException("Feed bevat geen podcast-enclosures (geen audio-bestanden)"))
        } else {
            Result.success(feed.title.orEmpty())
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun parse(feedUrl: String): SyndFeed {
        val req = HttpRequest.newBuilder().uri(URI.create(feedUrl))
            .header("User-Agent", "PersonalNewsFeed/1.0")
            .timeout(Duration.ofSeconds(5))
            .GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() >= 400) {
            throw IllegalStateException("HTTP ${resp.statusCode()}")
        }
        return SyndFeedInput().build(XmlReader(resp.body()))
    }

    /**
     * Ruwe duration-schatting uit enclosure-grootte (bytes). Veronderstelt
     * ~128 kbps MP3 = 16 KB/sec. Een betere getal komt later uit
     * Whisper's verbose_json `duration` — totdat dat binnen is, is dit
     * goed genoeg voor de feed-card.
     */
    private fun estimateDurationFromEnclosure(lengthBytes: Long): Int? {
        if (lengthBytes <= 0) return null
        return (lengthBytes / 16_000).toInt().takeIf { it > 0 }
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&[a-zA-Z]+;"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun logFetch(
        username: String, feedUrl: String, started: Instant,
        itemCount: Int, status: String, errorMessage: String?
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_RSS,
                    action = ExternalCall.ACTION_PODCAST_FEED_FETCH,
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
            log.warn("[PodcastFeed] could not log external_call: {}", e.message)
        }
    }
}
