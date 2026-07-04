package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.ai.WhisperClient
import com.vdzon.newsfeedbackend.media.AudioTranscoder
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastAudioDownloader
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * KAN-60: fase 2 van de tweefasen-pipeline — async transcript
 * ([processTranscript], door [PodcastTranscriptWorker] aangeroepen):
 *
 *   NEEDS_TRANSCRIPT → DOWNLOADING → TRANSCRIBING → SUMMARIZING → DONE
 *
 * Download MP3, Whisper, herberekening Claude. Overschrijft de summary
 * op het rss_items-kaartje en zet `summary_source='transcript'`
 * (badge verdwijnt). Trigget daarna de feed-promotie.
 *
 * **Foutbeleid**:
 *   - Whisper 429/5xx → episode blijft NEEDS_TRANSCRIPT, retry_count++,
 *     next_attempt_at = now + backoff (5m/15m/45m/24h). Geen FAILED.
 *   - Whisper fatale fout / geen API-key → episode wordt SHOW_NOTES_DONE
 *     (terminale "we proberen het niet meer"-state — badge blijft).
 *
 * Fase 1 is [PodcastShowNotesProcessor].
 */
@Component
class PodcastTranscriptProcessor(
    private val episodeRepo: PodcastEpisodeRepository,
    private val downloader: PodcastAudioDownloader,
    private val transcoder: AudioTranscoder,
    private val whisper: WhisperClient,
    private val summarizer: PodcastEpisodeSummarizer,
    private val cardWriter: PodcastCardWriter
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Whisper's harde limit is 26.214.400 bytes (25 MiB). We compress
         * naar deze drempel zodat we ruim onder de limit blijven (1 MiB
         * marge dekt eventuele afronding/header-overhead).
         */
        private const val MAX_WHISPER_BYTES = 25L * 1024 * 1024 - 1024 * 1024  // = 24 MiB
    }

    /**
     * Fase 2 — transcript-fase. Wordt synchroon aangeroepen vanuit
     * [PodcastTranscriptWorker] (de scheduled tick pakt MAX 1 episode op).
     * Retourneert hoe het is afgelopen zodat de worker bij rate-limit de
     * backoff kan instellen.
     */
    fun processTranscript(username: String, guid: String): TranscriptResult {
        MDC.put("username", username)
        var audioFile: java.io.File? = null
        // Aparte var voor 't (mogelijk gecomprimeerde) bestand dat naar
        // Whisper gaat. Kan dezelfde zijn als audioFile bij kleine episodes,
        // of een ge-transcodeerde temp-file bij grote (>24 MiB).
        var transcodedForWhisper: AudioTranscoder.TranscodeResult? = null
        return try {
            val initial = episodeRepo.get(username, guid)
                ?: return TranscriptResult.Skipped("episode verdwenen uit DB").also {
                    log.warn("[PodcastEpisode] transcript-fase: guid={} niet in DB", guid)
                }
            if (initial.status != PodcastEpisodeStatus.NEEDS_TRANSCRIPT) {
                log.debug("[PodcastEpisode] transcript-fase: guid={} status={} — skip", guid, initial.status)
                return TranscriptResult.Skipped("status=${initial.status}")
            }
            log.info("[PodcastEpisode] transcript-fase start guid={} title='{}' (retry_count={})",
                guid, initial.title.take(80), initial.retryCount)

            var ep = save(initial.copy(status = PodcastEpisodeStatus.DOWNLOADING))
            audioFile = downloader.download(username, guid, ep.audioUrl)
            if (audioFile == null) {
                log.warn("[PodcastEpisode] download faalde voor guid={} url={}", guid, ep.audioUrl)
                // Download-fouten zijn meestal niet-herstelbaar (404, dode
                // CDN-link); we laten de show-notes-card staan i.p.v. een
                // retry-storm te bouwen.
                save(ep.copy(
                    status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                    errorMessage = "Audio-download faalde — card blijft op show-notes"
                ))
                return TranscriptResult.Fatal("audio-download faalde")
            }

            ep = save(ep.copy(status = PodcastEpisodeStatus.TRANSCRIBING))
            // KAN-60-followup: Whisper accepteert max 25 MiB. ThoughtWorks-
            // afleveringen zaten net boven die limit en faalden met HTTP 413.
            // We comprimeren pre-upload naar mono 32 kbps MP3 wanneer 't
            // origineel te groot is. Bij ffmpeg-fout valt de transcoder
            // terug op het originele bestand zodat Whisper z'n eigen 413
            // teruggeeft en de bestaande SHOW_NOTES_DONE-fallback werkt.
            transcodedForWhisper = transcoder.ensureBelowSize(audioFile, MAX_WHISPER_BYTES)
            val outcome = whisper.transcribe(
                username = username,
                episodeGuid = guid,
                audioFile = transcodedForWhisper.file,
                audioFilename = guessFilename(ep.audioUrl),
                audioDurationSec = (ep.durationSeconds ?: 0).toLong()
            )
            when (outcome) {
                is WhisperClient.TranscribeOutcome.RateLimited -> {
                    // Niet FAILED — episode blijft in de retry-pool. De
                    // worker zet next_attempt_at + retry_count zelf na de
                    // return-value; hier zetten we 'm alleen terug op
                    // NEEDS_TRANSCRIPT en sturen de status-code mee.
                    save(ep.copy(
                        status = PodcastEpisodeStatus.NEEDS_TRANSCRIPT,
                        errorMessage = "Whisper rate-limited: ${outcome.message.take(160)}"
                    ))
                    log.warn("[PodcastEpisode] Whisper rate-limited guid={} ({}). Retry volgt via worker.",
                        guid, outcome.statusCode)
                    return TranscriptResult.RateLimited(outcome.statusCode)
                }
                is WhisperClient.TranscribeOutcome.NoApiKey,
                is WhisperClient.TranscribeOutcome.FatalError -> {
                    val msg = (outcome as? WhisperClient.TranscribeOutcome.FatalError)?.message
                        ?: "Whisper: geen API-key geconfigureerd"
                    // Geen Whisper-resultaat te krijgen; we stoppen met
                    // proberen en laten de show-notes-card permanent staan.
                    save(ep.copy(
                        status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                        errorMessage = "Whisper fataal: ${msg.take(160)}"
                    ))
                    log.warn("[PodcastEpisode] Whisper fatale fout guid={}: {} — card blijft op show-notes",
                        guid, msg)
                    return TranscriptResult.Fatal(msg)
                }
                is WhisperClient.TranscribeOutcome.Success -> {
                    val transcript = outcome.text
                    if (transcript.isBlank()) {
                        log.warn("[PodcastEpisode] Whisper gaf leeg transcript voor guid={}", guid)
                        save(ep.copy(
                            status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                            errorMessage = "Whisper gaf een leeg transcript terug"
                        ))
                        return TranscriptResult.Fatal("empty transcript")
                    }
                    ep = save(ep.copy(
                        status = PodcastEpisodeStatus.SUMMARIZING,
                        transcript = transcript
                    ))
                    val summarized = summarizer.summarize(username, ep, transcript)
                    if (summarized == null) {
                        // Claude faalde op het transcript — terugvallen op
                        // de bestaande show-notes-summary. Card blijft
                        // intact, badge ook (summarySource blijft show_notes).
                        save(ep.copy(
                            status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                            errorMessage = "Claude-samenvatting op transcript faalde"
                        ))
                        log.warn("[PodcastEpisode] Claude faalde op transcript guid={} — card blijft op show-notes", guid)
                        return TranscriptResult.Fatal("claude summarize failed")
                    }

                    val rssItemId = ep.rssItemId ?: UUID.randomUUID().toString()
                    cardWriter.upsertCard(username, ep, summarized, rssItemId, summarySource = "transcript")

                    save(ep.copy(
                        status = PodcastEpisodeStatus.DONE,
                        rssItemId = rssItemId,
                        summary = summarized.shortSummary,
                        // KAN-62: transcript-based long-summary +
                        // takeaways overschrijven de eerder uit show-notes
                        // gegenereerde versie. Beide nu inhoudelijk juist
                        // omdat we tot 80k chars transcript meesturen.
                        longSummary = summarized.longSummary.ifBlank { null },
                        keyTakeaways = summarized.keyTakeaways,
                        summarySource = "transcript",
                        nextAttemptAt = null,
                        errorMessage = null
                    ))
                    log.info("[PodcastEpisode] DONE (transcript-based) guid={} → rss_items.id={}",
                        guid, rssItemId)
                    cardWriter.triggerFeedPromotion(username, rssItemId)
                    return TranscriptResult.Success
                }
            }
        } catch (e: Exception) {
            log.error("[PodcastEpisode] onverwachte fout in transcript-fase guid={}: {}",
                guid, e.message, e)
            episodeRepo.get(username, guid)?.let {
                episodeRepo.upsert(it.copy(
                    status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                    errorMessage = "Onverwachte fout in transcript-fase: ${e.message ?: e.javaClass.simpleName}"
                ))
            }
            TranscriptResult.Fatal(e.message ?: e.javaClass.simpleName)
        } finally {
            // Ge-transcodeerde tussenfile opruimen als 't een aparte temp
            // was (anders is 'ie hetzelfde object als audioFile, één delete
            // is genoeg). Beide afzonderlijk in try/catch zodat een fout op
            // de ene de andere niet blokkeert.
            try {
                transcodedForWhisper?.takeIf { it.isTemporary }?.file?.delete()
            } catch (e: Exception) {
                log.warn("[PodcastEpisode] kon transcoded temp-file niet verwijderen: {}", e.message)
            }
            try {
                audioFile?.delete()
            } catch (e: Exception) {
                log.warn("[PodcastEpisode] kon temp-file niet verwijderen: {}", e.message)
            }
            MDC.clear()
        }
    }

    /**
     * Backoff-tabel uit de story (AC #4): 5m → 15m → 45m → 24h. retryCount
     * is het aantal mislukte pogingen vóór deze tick; retryCount=0 ná de
     * eerste 429 → wachttijd 5m.
     */
    fun nextRetryDelay(retryCount: Int): Duration = when (retryCount) {
        0 -> Duration.ofMinutes(5)
        1 -> Duration.ofMinutes(15)
        2 -> Duration.ofMinutes(45)
        else -> Duration.ofHours(24)
    }

    private fun save(ep: PodcastEpisode): PodcastEpisode = episodeRepo.upsert(ep)

    private fun guessFilename(audioUrl: String): String {
        val path = audioUrl.substringBefore('?').substringAfterLast('/')
        return if (path.isBlank() || !path.contains('.')) "audio.mp3" else path
    }

    sealed class TranscriptResult {
        object Success : TranscriptResult()
        data class RateLimited(val statusCode: Int) : TranscriptResult()
        data class Fatal(val message: String) : TranscriptResult()
        data class Skipped(val reason: String) : TranscriptResult()
    }
}
