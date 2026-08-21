package com.vdzon.newsfeedbackend.podcast_source.infrastructure

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.vdzon.newsfeedbackend.common.SsrfUrlValidator
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Haalt één podcast-RSS-feed op en parsed naar [PodcastFeedEpisode]s.
 *
 * Verschil met de gewone [com.vdzon.newsfeedbackend.rss.infrastructure.RssFetcher]:
 *   - hier verwachten we een `<enclosure>`-tag met de audio-URL;
 *   - we vissen `<itunes:duration>` uit de foreign-markup (rome herkent
 *     de itunes-namespace niet als first-class veld);
 *   - GUID komt uit `entry.uri` (Rome map `<guid>` daarop); we vallen
 *     terug op de enclosure-URL als 'guid' ontbreekt;
 *   - geen 4-dagen-vensterfilter — podcast-feeds zijn dunner gevuld dan
 *     nieuws-RSS, en we limiteren in de pipeline op de laatste 7 bij
 *     initial ingestie (AC #9).
 */
@Component
class PodcastFeedFetcher(
    private val callLogger: ExternalCallLogger,
    // Zie SettingsServiceImpl.ssrfAllowLoopback / RssFetcher — zelfde e2e-only escape-hatch,
    // hier voor de defense-in-depth-check vlak vóór het echte fetch-request.
    @param:Value("\${app.security.ssrf.allow-loopback:false}") private val ssrfAllowLoopback: Boolean = false,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    data class FetchResult(
        val ok: Boolean,
        val podcastName: String,
        val episodes: List<PodcastFeedEpisode>,
        val errorMessage: String? = null
    )

    data class PodcastFeedEpisode(
        val guid: String,
        val title: String,
        val audioUrl: String,
        val durationSeconds: Int?,
        val publishedDate: String?,
        val showNotes: String
    )

    fun fetch(feedUrl: String, username: String): FetchResult {
        val started = Instant.now()
        var status = "ok"
        var errorMessage: String? = null
        var itemCount = 0
        try {
            // Defense-in-depth: verse DNS-resolutie vlak vóór het versturen,
            // ook al is de URL al gevalideerd bij opslaan (dekt DNS-rebinding af).
            val validation = SsrfUrlValidator.validate(feedUrl, allowLoopback = ssrfAllowLoopback)
            if (validation is SsrfUrlValidator.ValidationResult.Invalid) {
                log.warn("[PodcastFeed] blocked SSRF-risky URL {}: {}", feedUrl, validation.reason)
                status = "error"
                errorMessage = "geblokkeerd: ${validation.reason}"
                return FetchResult(ok = false, podcastName = "", episodes = emptyList(), errorMessage = errorMessage)
            }
            val req = HttpRequest.newBuilder().uri(URI.create(feedUrl))
                .header("User-Agent", "PersonalNewsFeed/1.0")
                .timeout(Duration.ofSeconds(20))
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() >= 400) {
                status = "error"
                errorMessage = "http ${resp.statusCode()}"
                log.warn("[PodcastFeed] {} -> {}", feedUrl, resp.statusCode())
                return FetchResult(ok = false, podcastName = "", episodes = emptyList(), errorMessage = errorMessage)
            }
            val feed: SyndFeed = SyndFeedInput().build(XmlReader(resp.body()))
            val podcastName = feed.title.orEmpty()
            val episodes = feed.entries.mapNotNull { entry ->
                val enclosure = entry.enclosures.firstOrNull { it.url.isNullOrBlank().not() } ?: return@mapNotNull null
                val audioUrl = enclosure.url
                val guid = entry.uri.takeUnless { it.isNullOrBlank() } ?: audioUrl
                val pubDate = entry.publishedDate?.toInstant() ?: entry.updatedDate?.toInstant()
                val publishedDay = pubDate?.atZone(ZoneOffset.UTC)?.toLocalDate()
                    ?.format(DateTimeFormatter.ISO_LOCAL_DATE)
                PodcastFeedEpisode(
                    guid = guid,
                    title = entry.title.orEmpty(),
                    audioUrl = audioUrl,
                    durationSeconds = extractItunesDuration(entry),
                    publishedDate = publishedDay,
                    showNotes = stripHtml(entry.description?.value.orEmpty()).take(8000)
                )
            }
            itemCount = episodes.size
            return FetchResult(ok = true, podcastName = podcastName, episodes = episodes)
        } catch (e: Exception) {
            log.warn("[PodcastFeed] failed to fetch {}: {}", feedUrl, e.message)
            status = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            return FetchResult(ok = false, podcastName = "", episodes = emptyList(), errorMessage = errorMessage)
        } finally {
            callLogger.logCall(
                ExternalCall.PROVIDER_RSS, ExternalCall.ACTION_PODCAST_FEED_FETCH, username, started,
                ExternalCall.UNIT_ITEMS, status,
                units = itemCount.toLong(), costUsd = 0.0, errorMessage = errorMessage,
                subject = feedUrl.take(120)
            )
        }
    }

    /**
     * Probeert eerst rome's foreign-markup te raadplegen voor een
     * `<itunes:duration>`-tag. De waarde kan "MM:SS", "HH:MM:SS" of een
     * pure secondentelling zijn — alle drie ondersteunen we.
     */
    private fun extractItunesDuration(entry: com.rometools.rome.feed.synd.SyndEntry): Int? {
        val element = entry.foreignMarkup.firstOrNull {
            it.name.equals("duration", ignoreCase = true) &&
                it.namespaceURI.contains("itunes", ignoreCase = true)
        } ?: return null
        val raw = element.text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            if (":" in raw) {
                val parts = raw.split(":").map { it.toInt() }
                when (parts.size) {
                    2 -> parts[0] * 60 + parts[1]
                    3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                    else -> null
                }
            } else {
                raw.toInt()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&[a-zA-Z]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
