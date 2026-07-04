package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.ai.AiJson
import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.events.EventVideo
import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.events.infrastructure.EventVideoRepository
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.search.TavilyClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * KAN-66: ontdekt per al opgeslagen event de online video's (keynotes/
 * sessies) met Tavily web-search + Claude. Eigen job, los van de
 * [EventDiscoveryPipeline]: een aparte cron ([EventVideoScheduler]) en een
 * aparte handmatige trigger. Architectuur is een 1-op-1 mirror van de
 * event-discovery: een @Async @EventListener met een per-user ReentrantLock.
 *
 * Per ontdekte video:
 *  - dedup op de canonieke video-URL per (gebruiker, event); een bestaande
 *    video wordt bijgewerkt, niet gedupliceerd.
 *  - opgeslagen wordt: titel, video-URL en — indien beschikbaar — een
 *    Nederlandse beschrijving. Er wordt nog GEEN samenvatting gemaakt.
 */
@Component
class EventVideoDiscoveryPipeline(
    private val events: EventRepository,
    private val videos: EventVideoRepository,
    private val tavily: TavilyClient,
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    @EventListener
    @Async
    fun onDiscover(event: EventVideoDiscoveryRequested) = run(event.username)

    fun run(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[EventVideos] discovery already running for '{}'", username)
            return
        }
        MDC.put("username", username)
        try {
            val started = Instant.now()
            log.info("[EventVideos] start video-discovery voor '{}'", username)
            val candidates = events.load(username).filter { withinWindow(it.startDate) }
            if (candidates.isEmpty()) {
                log.info("[EventVideos] geen events binnen window voor '{}' — niets te zoeken", username)
                return
            }
            var newCount = 0
            var updatedCount = 0

            for (ev in candidates) {
                val known = videos.loadForEvent(username, ev.id)
                    .map { canonicalUrl(it.videoUrl) }
                    .toHashSet()
                val query = buildString {
                    append(ev.name)
                    ev.organization?.takeIf { it.isNotBlank() }?.let { append(" $it") }
                    append(" keynote sessions talks full video recording")
                }
                log.info("[EventVideos] event '{}' → Tavily-search: {}", ev.id, query)
                val results = tavily.search(username, query, days = 365, maxResults = 12)
                if (results.isEmpty()) {
                    log.info("[EventVideos]   geen zoekresultaten voor '{}'", ev.id)
                    continue
                }
                val discovered = extractVideos(username, ev, results).take(MAX_VIDEOS_PER_EVENT)
                log.info("[EventVideos]   AI haalde {} video's uit {} resultaten voor '{}'",
                    discovered.size, results.size, ev.id)
                for (video in discovered) {
                    val canon = canonicalUrl(video.videoUrl)
                    if (canon.isBlank()) continue
                    if (known.contains(canon)) {
                        videos.upsert(username, video.copy(videoUrl = canon, updatedAt = Instant.now()))
                        updatedCount++
                    } else {
                        videos.upsert(username, video.copy(videoUrl = canon))
                        known.add(canon)
                        newCount++
                        log.info("[EventVideos]   NIEUWE video '{}' voor event '{}'", video.title, ev.id)
                    }
                }
            }

            meters.counter("newsfeed.event_videos.discovered", "username", username).increment(newCount.toDouble())
            meters.timer("newsfeed.event_videos.discovery.duration", "username", username)
                .record(Duration.between(started, Instant.now()))
            val took = Duration.between(started, Instant.now()).seconds.toInt()
            log.info("[EventVideos] klaar voor '{}': {} nieuw, {} bijgewerkt, duur {}s",
                username, newCount, updatedCount, took)
        } catch (e: Exception) {
            log.error("[EventVideos] discovery mislukt voor '{}': {}", username, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun extractVideos(
        username: String,
        ev: Event,
        results: List<com.vdzon.newsfeedbackend.search.TavilyResult>
    ): List<EventVideo> {
        val sources = results.joinToString("\n\n") { r ->
            "URL: ${r.url}\nTitel: ${r.title}\nFragment: ${r.snippet.take(500)}"
        }
        val ai = openAi.complete(
            model = aiModels.modelOrDefault(ExternalCall.ACTION_EVENT_VIDEO_DISCOVERY),
            action = ExternalCall.ACTION_EVENT_VIDEO_DISCOVERY,
            username = username,
            subject = "Video's voor event ${ev.name}",
            maxOutputTokens = 8000,
            system = """
                Je bent een tech-video-analist. Uit zoekresultaten haal je de online
                video's (keynotes en sessies) van één specifiek tech-event. Het gaat om
                opnames die je echt online kunt bekijken (YouTube, Vimeo, conferentie-
                portal e.d.). Negeer aankondigingen, blogposts, ticketpagina's, podcasts
                en alles dat niet naar een concrete video-opname linkt.

                Regels:
                - Geef per video de directe video-URL waarop je 'm kunt bekijken.
                - Titel: de titel van de video/sessie.
                - description: een korte NEDERLANDSE beschrijving van waar de video over
                  gaat. Laat leeg ("") wanneer je dat niet uit de resultaten kunt afleiden.
                - Voeg alleen video's toe die duidelijk bij dit event horen.
                - Antwoord met ALLEEN een pure JSON-array, geen markdown-codefences (geen ```),
                  geen prose ervoor of erna. Een lege array [] is prima als je niets vindt.
            """.trimIndent(),
            user = """
                Event: ${ev.name}${ev.organization?.takeIf { it.isNotBlank() }?.let { " (georganiseerd door $it)" } ?: ""}
                ${ev.description.takeIf { it.isNotBlank() }?.let { "Waar het event over gaat: $it" } ?: ""}

                Zoekresultaten:
                $sources

                Antwoord met een JSON-array. Voor elke video één object:
                [{"title":"Keynote: the future of Java","url":"https://www.youtube.com/watch?v=...",
                  "description":"Nederlandse beschrijving van de inhoud"}]
            """.trimIndent()
        )
        return try {
            val tree = mapper.readTree(AiJson.extract(ai.text))
            if (!tree.isArray) {
                log.warn("[EventVideos] AI gaf geen JSON-array voor '{}' — eerste 300 chars: {}",
                    ev.id, ai.text.take(300))
                return emptyList()
            }
            tree.mapNotNull { node ->
                val url = node.path("url").asText("").trim()
                val title = node.path("title").asText("").trim()
                if (url.isBlank() || title.isBlank()) return@mapNotNull null
                EventVideo(
                    eventId = ev.id,
                    videoUrl = url,
                    title = title,
                    descriptionNl = node.path("description").asText(null)?.ifBlank { null }
                )
            }
        } catch (e: Exception) {
            log.warn("[EventVideos] parse-fout voor '{}': {} — eerste 300 chars: {}",
                ev.id, e.message, ai.text.take(300))
            emptyList()
        }
    }

    /** Houd events die in de toekomst liggen of maximaal één jaar terug zijn. */
    private fun withinWindow(startDate: String?): Boolean {
        if (startDate == null) return true
        val d = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return true
        return !d.isBefore(LocalDate.now().minusYears(1))
    }

    /**
     * Canonicaliseer een video-URL voor dedup: trim, lowercase scheme+host,
     * strip fragment en trailing slash. Query (bv. YouTube `?v=`) blijft
     * behouden omdat die de video identificeert.
     */
    private fun canonicalUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return try {
            val u = URI(trimmed)
            val scheme = u.scheme?.lowercase() ?: return trimmed.trimEnd('/')
            val host = u.host?.lowercase() ?: return trimmed.trimEnd('/')
            val path = (u.path ?: "").trimEnd('/')
            val query = u.query?.let { "?$it" } ?: ""
            "$scheme://$host$path$query"
        } catch (e: Exception) {
            trimmed.trimEnd('/')
        }
    }


    companion object {
        /** Plafond op video's per event per run — beperkt Tavily/Claude-kosten. */
        private const val MAX_VIDEOS_PER_EVENT = 10
    }
}
