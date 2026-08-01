package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastTranscriptRequested
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.rss.PodcastPromotionRequested
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * SF-1739: uurlijkse recovery/reconciliation-job voor de podcast-
 * transcriptfase. Opvolger van de oude `PodcastTranscriptWorker`, die
 * elke twee minuten de database bevroeg en zo Neon-scale-to-zero
 * blokkeerde.
 *
 * Dit is uitdrukkelijk een **vangnet**, geen normale route: een verse
 * aflevering start via het [PodcastTranscriptRequested]-event dat
 * [PodcastShowNotesProcessor] publiceert. De job draait hoogstens één
 * keer per uur en pakt op:
 *
 *  1. **Gemiste/retry-klare afleveringen** — `status=NEEDS_TRANSCRIPT`
 *     met verlopen (of lege) `next_attempt_at`. Dekt zowel het event dat
 *     door een restart verloren ging als een backoff-retry die aan de
 *     beurt is. De job doet zelf géén Whisper-werk maar publiceert per
 *     aflevering hetzelfde event, zodat alles via dezelfde
 *     single-threaded [PodcastTranscriptPipeline] loopt (max 1 tegelijk).
 *  2. **Show-notes-timeout-promotie** — een aflevering die langer dan
 *     [promotionTimeout] (default 24h) op NEEDS_TRANSCRIPT staat en nog
 *     niet naar de Feed-tab is gepromoot, gaat alsnog door de
 *     feed-promotie op basis van de show-notes-samenvatting. De marker
 *     `feed_promotion_attempted_at` wordt gezet **vóór** het publishen,
 *     zodat een AI-afwijzing geen herhaalde Claude-calls oplevert.
 *
 * Configurable via:
 *   - `app.podcast.recovery.cron` (default `0 5 * * * *` = elk uur op :05;
 *     `-` schakelt de job uit, o.a. gebruikt in de e2e-suite)
 *   - `app.podcast.transcript-worker.promotion-timeout-hours` (default 24)
 */
@Component
class PodcastRecoveryScheduler(
    private val episodeRepo: PodcastEpisodeRepository,
    private val events: ApplicationEventPublisher,
    @Value("\${app.podcast.transcript-worker.promotion-timeout-hours:24}") promotionTimeoutHours: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val promotionTimeout: Duration = Duration.ofHours(promotionTimeoutHours)

    companion object {
        /**
         * Bovengrens op het aantal afleveringen dat één recovery-run
         * hertriggert. De verwerking zelf blijft serieel (één executor-
         * thread); deze cap voorkomt alleen dat één run een enorme
         * achterstand in één keer in de queue duwt.
         */
        const val MAX_EPISODES_PER_RUN = 10
    }

    @Scheduled(cron = "\${app.podcast.recovery.cron:0 5 * * * *}")
    @SchedulerLock(name = "podcastRecovery", lockAtMostFor = "50m", lockAtLeastFor = "1m")
    fun recover() {
        val now = Instant.now()
        try {
            retriggerPendingTranscripts(now)
        } catch (e: Exception) {
            log.error("[PodcastRecovery] stap 'transcript-hertrigger' faalde: {}", e.message, e)
        }
        try {
            promoteShowNotesTimeouts(now)
        } catch (e: Exception) {
            log.error("[PodcastRecovery] stap 'show-notes-promotie' faalde: {}", e.message, e)
        }
    }

    private fun retriggerPendingTranscripts(now: Instant) {
        val ready = episodeRepo.findReadyForTranscript(now, MAX_EPISODES_PER_RUN)
        if (ready.isEmpty()) {
            log.debug("[PodcastRecovery] geen achterstallige afleveringen")
            return
        }
        log.info("[PodcastRecovery] {} achterstallige aflevering(en) hertriggeren", ready.size)
        for (ep in ready) {
            log.info("[PodcastRecovery] hertrigger transcript: user='{}' guid={} retryCount={}",
                ep.username, ep.guid, ep.retryCount)
            events.publishEvent(PodcastTranscriptRequested(username = ep.username, guid = ep.guid))
        }
    }

    private fun promoteShowNotesTimeouts(now: Instant) {
        val expired = episodeRepo.findShowNotesExpiredForPromotion(now, promotionTimeout)
        if (expired.isEmpty()) return
        log.info("[PodcastRecovery] {} episode(s) >{}h op show-notes — feed-promotie triggeren",
            expired.size, promotionTimeout.toHours())
        for (ep in expired) {
            val rid = ep.rssItemId ?: continue
            // V8-fix: markeer de poging vóór we het event publishen.
            // De event-handler is @Async én de AI wijst veel podcasts af
            // (laat rss_items.feed_item_id NULL); zonder deze marker zou
            // de query iedere run opnieuw matchen en dezelfde aflevering
            // opnieuw naar de AI blijven sturen.
            try {
                episodeRepo.markFeedPromotionAttempted(ep.username, ep.guid, now)
            } catch (e: Exception) {
                log.warn("[PodcastRecovery] kon promotion-marker niet zetten voor guid={}: {} — sla deze run over om loop te voorkomen",
                    ep.guid, e.message)
                continue
            }
            try {
                events.publishEvent(PodcastPromotionRequested(username = ep.username, rssItemId = rid))
                log.info("[PodcastRecovery] show-notes-timeout-promotie getriggerd: user='{}' guid={} rssItemId={}",
                    ep.username, ep.guid, rid)
            } catch (e: Exception) {
                log.warn("[PodcastRecovery] kon promotion-event niet publiceren voor guid={}: {}",
                    ep.guid, e.message)
            }
        }
    }
}
