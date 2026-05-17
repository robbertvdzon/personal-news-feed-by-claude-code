package com.vdzon.newsfeedbackend.podcastfeed.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.podcastfeed.EpisodeStatus
import com.vdzon.newsfeedbackend.podcastfeed.PodcastEpisode
import com.vdzon.newsfeedbackend.podcastfeed.infrastructure.PodcastAudioFetcher
import com.vdzon.newsfeedbackend.podcastfeed.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.podcastfeed.infrastructure.PodcastFeedFetcher
import com.vdzon.newsfeedbackend.podcastfeed.infrastructure.PodcastFeedRepository
import com.vdzon.newsfeedbackend.podcastfeed.infrastructure.WhisperClient
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

data class PodcastRefreshRequested(val username: String)

/**
 * Async pipeline voor podcast-bronnen (KAN-54):
 *
 *   PENDING → DOWNLOADING → TRANSCRIBING* → SUMMARIZING → DONE
 *                                                       ↘ FAILED
 *
 * (`*` = alleen als de bron transcribe-enabled is.)
 *
 * Op een refresh:
 *  1. Voor elke podcast-feed van de user: parse XML, voeg nieuwe
 *     episodes (per GUID) toe als PENDING. Bestaande GUIDs worden
 *     overgeslagen — daarmee is `3× refresh` idempotent (AC6).
 *  2. Verwerk elke niet-afgeronde episode: download → (optioneel)
 *     Whisper → Claude-samenvatting → maak FeedItem aan.
 *
 * Foute episodes blijven op FAILED met error_message; ze verschijnen
 * NIET als feed-card (AC8).
 */
@Component
class PodcastFeedPipeline(
    private val feedRepo: PodcastFeedRepository,
    private val episodeRepo: PodcastEpisodeRepository,
    private val fetcher: PodcastFeedFetcher,
    private val audio: PodcastAudioFetcher,
    private val whisper: WhisperClient,
    private val anthropic: AnthropicClient,
    private val feedService: FeedService,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    @EventListener
    @Async
    fun onRefresh(event: PodcastRefreshRequested) = run(event.username)

    fun run(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[PodcastFeed] refresh already running for '{}'", username)
            return
        }
        MDC.put("username", username)
        try {
            val feeds = feedRepo.load(username)
            if (feeds.isEmpty()) {
                log.info("[PodcastFeed] no feeds for '{}', skip", username)
                return
            }
            log.info("[PodcastFeed] start refresh for '{}', {} feeds", username, feeds.size)

            // Stap 1: per feed nieuwe afleveringen detecteren en als PENDING wegschrijven.
            for (feed in feeds) {
                val episodes = fetcher.fetch(feed.url, username)
                val added = episodes.count { ep ->
                    episodeRepo.insertIfAbsent(username, ep)
                }
                if (added > 0) log.info("[PodcastFeed] feed='{}' new episodes: {}", feed.url, added)
            }

            // Stap 2: verwerk alles wat nog niet DONE of FAILED is. Sequentieel om
            //          Whisper-pieken te dempen; bij een 503 wachten we vanzelf op
            //          de volgende refresh-tick.
            val pending = episodeRepo.findPending(username)
            if (pending.isEmpty()) {
                log.info("[PodcastFeed] no pending episodes for '{}'", username)
                return
            }
            log.info("[PodcastFeed] processing {} episode(s) for '{}'", pending.size, username)
            for (ep in pending) {
                processEpisode(username, ep)
            }
        } catch (e: Exception) {
            log.error("[PodcastFeed] refresh failed for '{}': {}", username, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun processEpisode(username: String, episode: PodcastEpisode) {
        val transcribeEnabled = feedRepo.find(username, episode.feedUrl)?.transcribeEnabled ?: true
        try {
            var current = episode
            // 1) audio downloaden (alleen als we gaan transcriberen)
            var transcript: String? = null
            var actualDuration: Int? = current.durationSeconds
            if (transcribeEnabled) {
                current = save(username, current.copy(status = EpisodeStatus.DOWNLOADING))
                val bytes = audio.fetch(username, current.audioUrl)
                    ?: return fail(username, current, "kon audio niet downloaden van ${current.audioUrl}")
                current = save(username, current.copy(status = EpisodeStatus.TRANSCRIBING))
                val whisperResult = whisper.transcribe(username, current.title.take(120), bytes)
                    ?: return fail(username, current, "Whisper-transcriptie mislukt (zie [Whisper]-log)")
                transcript = whisperResult.text
                actualDuration = whisperResult.durationSeconds.takeIf { it > 0 } ?: actualDuration
                current = save(
                    username, current.copy(
                        transcript = transcript,
                        durationSeconds = actualDuration
                    )
                )
            }

            // 2) Claude-samenvatting (transcript of show-notes)
            current = save(username, current.copy(status = EpisodeStatus.SUMMARIZING))
            val (summarySource, summarizerInput) = if (transcript != null && transcript.isNotBlank()) {
                "transcript" to transcript
            } else {
                "show_notes" to current.description.ifBlank { current.title }
            }
            val claude = summarize(username, current, summarizerInput, summarySource)
                ?: return fail(username, current, "Claude-samenvatting mislukt — zie [Anthropic]-log")

            // 3) FeedItem aanmaken zodat de aflevering in de feed-tab verschijnt
            val feedItem = FeedItem(
                id = UUID.randomUUID().toString(),
                title = current.title,
                titleNl = claude.titleNl.ifBlank { current.title },
                summary = claude.longSummary.ifBlank { summarizerInput },
                shortSummary = claude.shortSummary.ifBlank { current.description.take(200) },
                url = current.audioUrl,
                category = claude.category.ifBlank { "overig" },
                source = current.podcastName,
                sourceUrls = listOf(current.audioUrl),
                topics = claude.topics,
                feedReason = "Podcast-aflevering uit '${current.podcastName}'",
                createdAt = Instant.now(),
                publishedDate = current.publishedDate,
                kind = "podcast",
                audioUrl = current.audioUrl,
                durationSeconds = actualDuration,
                summarySource = summarySource
            )
            feedService.save(username, feedItem)

            save(
                username, current.copy(
                    summary = claude.longSummary,
                    summarySource = summarySource,
                    status = EpisodeStatus.DONE,
                    processedAt = Instant.now(),
                    feedItemId = feedItem.id,
                    errorMessage = null
                )
            )
            log.info(
                "[PodcastFeed] DONE episode='{}' source={} feedItemId={}",
                current.title.take(60), summarySource, feedItem.id
            )
        } catch (e: Exception) {
            log.error("[PodcastFeed] processEpisode crashed for guid={}: {}", episode.guid, e.message, e)
            fail(username, episode, "interne fout: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private data class ClaudePodcastSummary(
        val titleNl: String = "",
        val shortSummary: String = "",
        val longSummary: String = "",
        val category: String = "overig",
        val topics: List<String> = emptyList()
    )

    private fun summarize(
        username: String, episode: PodcastEpisode, text: String, source: String
    ): ClaudePodcastSummary? {
        val maxInput = 40_000  // ~5-50k woorden uit transcript; we kappen op tekens om Claude-cost te beperken
        val truncated = text.take(maxInput)
        val sourceLabel = if (source == "transcript") "transcript van de aflevering" else "show-notes (RSS-description)"
        val ai = try {
            anthropic.complete(
                operation = "summarizePodcastEpisode",
                action = ExternalCall.ACTION_PODCAST_SUMMARIZE_EPISODE,
                username = username,
                subject = episode.title.take(120),
                maxTokens = 4000,
                system = """
                    Je schrijft een Nederlandstalige samenvatting van een podcast-aflevering voor een softwareontwikkelaar.

                    Lever JSON met velden:
                    - titleNl: Korte Nederlandse titel (max 70 tekens), géén leestekens op het eind.
                    - shortSummary: 2 regels plain-text (~30-50 woorden) — teaser-style, eindig met een punt.
                    - longSummary: 3 hoofdpunten + optioneel 1 letterlijke quote uit de aflevering + waarom dit relevant is voor de luisteraar. 250-450 woorden, géén markdown-headers (#), wél **vet** voor begrippen.
                    - category: één van de meegegeven categorie-ids. Pak 'overig' als niets past.
                    - topics: 3-6 korte trefwoorden.

                    Antwoord uitsluitend met geldig JSON, geen markdown-codefences, geen prose ervoor of erna.
                """.trimIndent(),
                user = buildString {
                    appendLine("Podcast: ${episode.podcastName}")
                    appendLine("Aflevering: ${episode.title}")
                    if (!episode.publishedDate.isNullOrBlank()) appendLine("Datum: ${episode.publishedDate}")
                    appendLine("Bron-tekst is het $sourceLabel.")
                    appendLine()
                    appendLine("Categorie-ids om uit te kiezen: kotlin, flutter, ai, blockchain, spring, web_dev, overig")
                    appendLine()
                    appendLine("Bron-tekst:")
                    append(truncated)
                }
            )
        } catch (e: Exception) {
            log.warn("[PodcastFeed] Claude-summary call failed: {}", e.message)
            return null
        }
        return try {
            val tree = mapper.readTree(extractJson(ai.text))
            ClaudePodcastSummary(
                titleNl = tree.path("titleNl").asText("").trim(),
                shortSummary = tree.path("shortSummary").asText("").trim(),
                longSummary = tree.path("longSummary").asText("").trim(),
                category = tree.path("category").asText("overig").ifBlank { "overig" },
                topics = tree.path("topics").map { it.asText("") }.filter { it.isNotBlank() }
            )
        } catch (e: Exception) {
            log.warn("[PodcastFeed] could not parse Claude summary for '{}': {}", episode.title, e.message)
            // Fallback zodat we tóch iets in de feed-card kunnen tonen.
            ClaudePodcastSummary(longSummary = ai.text)
        }
    }

    private fun save(username: String, ep: PodcastEpisode): PodcastEpisode {
        episodeRepo.upsert(username, ep)
        return ep
    }

    private fun fail(username: String, ep: PodcastEpisode, message: String) {
        log.warn("[PodcastFeed] FAILED guid={} title='{}' reason={}", ep.guid, ep.title.take(60), message)
        episodeRepo.upsert(
            username, ep.copy(
                status = EpisodeStatus.FAILED,
                errorMessage = message,
                processedAt = Instant.now()
            )
        )
    }

    /** Lichte JSON-extractor, kopie van RssRefreshPipeline.extractJson. */
    private fun extractJson(text: String): String {
        var s = text.trim()
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.dropLast(3).trim()
        val curly = s.indexOf('{')
        val bracket = s.indexOf('[')
        val start = when {
            curly < 0 && bracket < 0 -> return s
            curly < 0 -> bracket
            bracket < 0 -> curly
            else -> minOf(curly, bracket)
        }
        val openChar = s[start]
        val closeChar = if (openChar == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (inString) {
                if (c == '\\') escape = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return s.substring(start)
    }
}
