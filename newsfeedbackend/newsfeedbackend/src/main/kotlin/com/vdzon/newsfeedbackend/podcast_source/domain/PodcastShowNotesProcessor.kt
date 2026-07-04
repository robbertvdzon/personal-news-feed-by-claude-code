package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * KAN-60: fase 1 van de tweefasen-pipeline — snelle show-notes-card
 * ([processShowNotes], async via `podcastTaskExecutor`):
 *
 *   PENDING → SUMMARIZING_FROM_NOTES → (NEEDS_TRANSCRIPT óf
 *   SHOW_NOTES_DONE als transcribeEnabled=false)
 *
 * Geen audio-download, geen Whisper-call. Claude vat de RSS-
 * `<description>` samen, het kaartje verschijnt direct in de RSS-tab
 * met `summary_source='show_notes'` (voorlopige badge in de UI).
 *
 * **Foutbeleid**: onverwachte exception in de show-notes-fase →
 * FAILED, geen card. Fase 2 is [PodcastTranscriptProcessor].
 */
@Component
class PodcastShowNotesProcessor(
    private val episodeRepo: PodcastEpisodeRepository,
    private val summarizer: PodcastEpisodeSummarizer,
    private val cardWriter: PodcastCardWriter
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Fase 1 — show-notes-summary. Wordt vanuit
     * [PodcastIngestionPipeline] gekickt direct na het ontdekken van een
     * nieuwe aflevering, zodat de card binnen seconden in de RSS-tab
     * verschijnt (AC #1).
     */
    @Async("podcastTaskExecutor")
    fun processShowNotes(username: String, guid: String, transcribeEnabled: Boolean) {
        MDC.put("username", username)
        try {
            val initial = episodeRepo.get(username, guid) ?: run {
                log.warn("[PodcastEpisode] verdween uit DB voor we 'm konden verwerken — guid={}", guid)
                return
            }
            // Idempotency: alleen in PENDING-staat verwerken; bij een refresh
            // die op een al-verwerkte episode landt (b.v. SUMMARIZING_FROM_NOTES
            // door een vorige tick) niet opnieuw doen.
            if (initial.status != PodcastEpisodeStatus.PENDING) {
                log.debug("[PodcastEpisode] guid={} status={} — skip show-notes-fase", guid, initial.status)
                return
            }
            log.info("[PodcastEpisode] show-notes-fase start guid={} title='{}'",
                guid, initial.title.take(80))

            var ep = save(initial.copy(status = PodcastEpisodeStatus.SUMMARIZING_FROM_NOTES))

            if (ep.showNotes.isBlank()) {
                log.warn("[PodcastEpisode] geen show-notes — guid={} (kan geen voorlopige card maken)", guid)
                save(ep.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Geen show-notes om een voorlopige samenvatting van te maken"
                ))
                return
            }

            val summarized = summarizer.summarize(username, ep, ep.showNotes)
            if (summarized == null) {
                save(ep.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Claude-samenvatting op show-notes faalde"
                ))
                return
            }

            val rssItemId = ep.rssItemId ?: UUID.randomUUID().toString()
            cardWriter.upsertCard(username, ep, summarized, rssItemId, summarySource = "show_notes")

            // Bij transcribeEnabled=false: dit is meteen de eindstaat. Card
            // krijgt permanent een show-notes-badge (refiner-aanname), en
            // we promoten 'm direct (geen wachten op transcript dat nooit
            // komt — voorkomt dat een card 24h "vast" zit voordat de
            // timeout-promotie 'm oppakt).
            val nextStatus = if (transcribeEnabled) {
                PodcastEpisodeStatus.NEEDS_TRANSCRIPT
            } else {
                PodcastEpisodeStatus.SHOW_NOTES_DONE
            }
            ep = save(ep.copy(
                status = nextStatus,
                rssItemId = rssItemId,
                summary = summarized.shortSummary,
                // KAN-62: vul de detail-velden vanaf show-notes-fase zodat
                // het detail-scherm al iets te tonen heeft voordat het
                // transcript binnen is. Bij de latere transcript-fase
                // worden ze met inhoudelijk rijkere versies overschreven.
                longSummary = summarized.longSummary.ifBlank { null },
                keyTakeaways = summarized.keyTakeaways,
                summarySource = "show_notes",
                errorMessage = null
            ))

            if (!transcribeEnabled) {
                cardWriter.triggerFeedPromotion(username, rssItemId)
            }
            log.info("[PodcastEpisode] show-notes-fase klaar guid={} → status={} rssItemId={}",
                guid, nextStatus, rssItemId)
        } catch (e: Exception) {
            log.error("[PodcastEpisode] onverwachte fout in show-notes-fase guid={}: {}", guid, e.message, e)
            episodeRepo.get(username, guid)?.let {
                episodeRepo.upsert(it.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Onverwachte fout: ${e.message ?: e.javaClass.simpleName}"
                ))
            }
        } finally {
            MDC.clear()
        }
    }

    private fun save(ep: PodcastEpisode): PodcastEpisode = episodeRepo.upsert(ep)
}
