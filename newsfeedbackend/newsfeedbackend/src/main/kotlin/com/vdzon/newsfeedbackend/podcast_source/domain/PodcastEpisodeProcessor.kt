package com.vdzon.newsfeedbackend.podcast_source.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastAudioDownloader
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.WhisperClient
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.infrastructure.RssItemRepository
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Async-pipeline-stappen voor één podcast-aflevering:
 *   DOWNLOADING → TRANSCRIBING → SUMMARIZING → DONE
 *
 * Op elke fout (geen audio, Whisper-fail, Claude-fail) zetten we de
 * episode op FAILED met een leesbare `error_message`. We schrijven dan
 * GEEN rss_items-rij; gefaalde episodes verschijnen niet als card
 * (AC #8).
 *
 * Bij `transcribeEnabled=false` (of als Whisper faalt en er show-notes
 * zijn): val terug op de show-notes als Claude-input. Beter een matige
 * samenvatting dan geen card.
 */
@Component
class PodcastEpisodeProcessor(
    private val episodeRepo: PodcastEpisodeRepository,
    private val rssRepo: RssItemRepository,
    private val downloader: PodcastAudioDownloader,
    private val whisper: WhisperClient,
    private val anthropic: AnthropicClient,
    private val settings: SettingsService,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("podcastTaskExecutor")
    fun process(username: String, guid: String, transcribeEnabled: Boolean) {
        MDC.put("username", username)
        var audioFile: java.io.File? = null
        try {
            val initial = episodeRepo.get(username, guid) ?: run {
                log.warn("[PodcastEpisode] verdween uit DB voor we 'm konden verwerken — guid={}", guid)
                return
            }
            // Idempotency: als 'ie al DONE staat (b.v. door een eerder
            // gevallen async-run die uiteindelijk toch slaagde) niet
            // opnieuw verwerken. AC #6 — 3× refresh = 0 dupes.
            if (initial.status == PodcastEpisodeStatus.DONE) {
                log.debug("[PodcastEpisode] guid={} al DONE — skip", guid)
                return
            }
            log.info("[PodcastEpisode] start guid={} title='{}'", guid, initial.title.take(80))

            var ep = initial
            val transcript = if (transcribeEnabled) {
                ep = save(ep.copy(status = PodcastEpisodeStatus.DOWNLOADING))
                audioFile = downloader.download(username, guid, ep.audioUrl)
                if (audioFile == null) {
                    log.warn("[PodcastEpisode] download faalde voor guid={} url={}", guid, ep.audioUrl)
                    ep = save(ep.copy(
                        status = PodcastEpisodeStatus.FAILED,
                        errorMessage = "Audio-download faalde"
                    ))
                    return
                }
                ep = save(ep.copy(status = PodcastEpisodeStatus.TRANSCRIBING))
                val result = whisper.transcribe(
                    username = username,
                    episodeGuid = guid,
                    audioFile = audioFile,
                    audioFilename = guessFilename(ep.audioUrl),
                    audioDurationSec = (ep.durationSeconds ?: 0).toLong()
                )
                result?.text.orEmpty()
            } else {
                log.info("[PodcastEpisode] transcribe-toggle uit — fallback naar show-notes voor guid={}", guid)
                ""
            }

            val summarizeInput = transcript.ifBlank { ep.showNotes }
            if (summarizeInput.isBlank()) {
                log.warn("[PodcastEpisode] noch transcript noch show-notes — guid={}", guid)
                save(ep.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Geen transcript en geen show-notes om samenvatting van te maken"
                ))
                return
            }

            ep = save(ep.copy(status = PodcastEpisodeStatus.SUMMARIZING, transcript = transcript))
            val summarized = summarize(username, ep, summarizeInput)
            if (summarized == null) {
                save(ep.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Claude-samenvatting faalde"
                ))
                return
            }

            ep = ep.copy(summary = summarized.shortSummary)
            val rssItemId = ep.rssItemId ?: UUID.randomUUID().toString()
            val rss = buildRssItem(ep, summarized, rssItemId)
            rssRepo.upsert(username, rss)

            save(ep.copy(
                status = PodcastEpisodeStatus.DONE,
                rssItemId = rssItemId,
                errorMessage = null
            ))
            log.info("[PodcastEpisode] DONE guid={} → rss_items.id={}", guid, rssItemId)
        } catch (e: Exception) {
            log.error("[PodcastEpisode] onverwachte fout guid={}: {}", guid, e.message, e)
            episodeRepo.get(username, guid)?.let {
                episodeRepo.upsert(it.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Onverwachte fout: ${e.message ?: e.javaClass.simpleName}"
                ))
            }
        } finally {
            try {
                audioFile?.delete()
            } catch (e: Exception) {
                log.warn("[PodcastEpisode] kon temp-file niet verwijderen: {}", e.message)
            }
            MDC.clear()
        }
    }

    private fun save(ep: PodcastEpisode): PodcastEpisode = episodeRepo.upsert(ep)

    private data class Summarized(
        val shortSummary: String,
        val category: String,
        val topics: List<String>
    )

    private fun summarize(username: String, ep: PodcastEpisode, input: String): Summarized? {
        val categories = settings.getCategories(username).filter { it.enabled || it.isSystem }
        val catList = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) " — ${c.extraInstructions.take(200)}" else ""
            "- ${c.id}: ${c.name}$instr"
        }
        // Whisper-transcripts kunnen 5k-50k woorden lang zijn → afkappen
        // op ~12000 tekens van het begin en het laatste stuk middelpunt
        // overslaan voor Claude. De korte samenvatting verliest weinig
        // door de afkap; voor de lange samenvatting (Feed-tab) gebruikt
        // de bestaande RSS-pipeline een uitgebreidere selectie.
        val sample = if (input.length > 12_000) input.take(12_000) + "\n[...afgekort wegens lengte...]" else input
        val ai = anthropic.complete(
            operation = "summarizePodcastEpisode",
            action = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_PODCAST_EPISODE_SUMMARIZE,
            username = username,
            subject = "Podcast '${ep.podcastName.take(40)}' — ${ep.title.take(80)}",
            model = anthropic.summaryModel(),
            system = """
                Je vat podcast-afleveringen samen in het Nederlands.

                shortSummary: 1-2 zinnen (~30-50 woorden, plain text — geen markdown) die in 1 oogopslag duidelijk maken waar deze aflevering over gaat. Eindig met een punt.

                topics: 3-8 korte Nederlandse onderwerpen die in de aflevering aan bod zijn gekomen — dit zijn de "alle onderwerpen waar ze het over gehad hebben" uit de story. Pak concrete inhoudelijke topics, geen marketing-woorden.

                category: kies één id uit de gebruikersvoorkeuren hieronder (fallback "overig").

                Antwoord uitsluitend met geldig JSON, geen markdown-codefences (geen ```), geen prose ervoor of erna.
            """.trimIndent(),
            user = buildString {
                appendLine("Podcast: ${ep.podcastName}")
                appendLine("Aflevering: ${ep.title}")
                if (!ep.publishedDate.isNullOrBlank()) appendLine("Datum: ${ep.publishedDate}")
                if ((ep.durationSeconds ?: 0) > 0) appendLine("Duur: ${(ep.durationSeconds ?: 0) / 60} min")
                appendLine()
                appendLine("Beschikbare categorieën (id, naam, voorkeur):")
                appendLine(catList)
                appendLine()
                appendLine("Input (transcript of show-notes; mogelijk afgekapt):")
                appendLine(sample)
                appendLine()
                appendLine("Antwoord met JSON:")
                append("""{"shortSummary": "...", "category": "kotlin", "topics": ["..."]}""")
            }
        )
        val raw = ai.text.trim()
        if (raw.isBlank()) {
            log.warn("[PodcastEpisode] Claude gaf lege response voor guid={}", ep.guid)
            return null
        }
        return try {
            val tree = mapper.readTree(extractJson(raw))
            val shortSum = tree.path("shortSummary").asText("").trim()
            val cat = tree.path("category").asText("overig").ifBlank { "overig" }
            val topics = tree.path("topics").mapNotNull { it.asText().takeUnless { t -> t.isBlank() } }
            if (shortSum.isBlank()) {
                log.warn("[PodcastEpisode] Claude gaf geen shortSummary voor guid={}", ep.guid)
                return null
            }
            Summarized(shortSummary = shortSum, category = cat, topics = topics)
        } catch (e: Exception) {
            log.warn("[PodcastEpisode] parse-fout in Claude-response voor guid={}: {} — head: {}",
                ep.guid, e.message, raw.take(300))
            null
        }
    }

    private fun buildRssItem(ep: PodcastEpisode, sum: Summarized, rssItemId: String): RssItem {
        // Bij re-runs hergebruiken we het oude rss_items.id zodat
        // gebruikersinteractie (sterren, isRead) bewaard blijft.
        val existing = ep.rssItemId?.let { rid ->
            rssRepo.load(ep.username).find { it.id == rid }
        }
        return (existing ?: RssItem(
            id = rssItemId,
            title = ep.title,
            url = ep.audioUrl.ifBlank { ep.feedUrl },
            feedUrl = ep.feedUrl,
            source = ep.podcastName,
            timestamp = Instant.now(),
            mediaType = "PODCAST",
            audioUrl = ep.audioUrl,
            durationSeconds = ep.durationSeconds
        )).copy(
            title = ep.title,
            summary = sum.shortSummary,
            url = ep.audioUrl.ifBlank { ep.feedUrl },
            feedUrl = ep.feedUrl,
            source = ep.podcastName,
            snippet = ep.showNotes.take(1000),
            publishedDate = ep.publishedDate,
            category = sum.category,
            topics = sum.topics,
            mediaType = "PODCAST",
            audioUrl = ep.audioUrl,
            durationSeconds = ep.durationSeconds,
            processedAt = Instant.now()
        )
    }

    private fun guessFilename(audioUrl: String): String {
        val path = audioUrl.substringBefore('?').substringAfterLast('/')
        return if (path.isBlank() || !path.contains('.')) "audio.mp3" else path
    }

    /**
     * Pakt het eerste balanced JSON-object uit een tekst. Claude wikkelt
     * regelmatig JSON in ```json ... ``` of zet er prose voor. We
     * doen hier exact dezelfde truc als in [com.vdzon.newsfeedbackend.rss.domain.RssRefreshPipeline].
     */
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
                if (c == '\\') escape = true
                else if (c == '"') inString = false
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
