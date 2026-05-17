package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.rss.domain.PodcastPromotionRequested
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * KAN-60: scheduled tick die de async transcript-fase aandrijft.
 *
 * Twee verantwoordelijkheden per tick:
 *
 *  1. **Transcript verwerken** — pak één aflevering met
 *     `status=NEEDS_TRANSCRIPT` en `next_attempt_at <= now()`, en draai
 *     [PodcastEpisodeProcessor.processTranscript]. Op 429/5xx wordt
 *     `retry_count++` en `next_attempt_at = now + backoff` geschreven
 *     volgens AC #4 (5m / 15m / 45m / 24h). MAX 1 aflevering per tick →
 *     spreidt de Whisper-load (AC #3, beschermt tegen het KAN-59-
 *     incident waar 7 episodes tegelijk in de quota liepen).
 *
 *  2. **Show-notes-timeout-promotie** — een aflevering die langer dan
 *     [promotionTimeout] (default 24h) op NEEDS_TRANSCRIPT staat en
 *     nog niet naar de Feed-tab is gepromoot, gaat alsnog door de
 *     feed-promotie op basis van de show-notes-summary. De aflevering
 *     blijft `NEEDS_TRANSCRIPT` zodat de transcript-poging actief blijft;
 *     alleen de feed-promotie wordt eenmalig getriggerd (de promoter
 *     skipt items met een bestaande `feed_item_id`, dus geen dubbele
 *     promotie als het transcript later alsnog binnenkomt).
 *
 * Configurable via:
 *   - `app.podcast.transcript-worker.interval-ms` (default 120000 = 2m)
 *   - `app.podcast.transcript-worker.promotion-timeout-hours` (default 24)
 */
@Component
class PodcastTranscriptWorker(
    private val episodeRepo: PodcastEpisodeRepository,
    private val processor: PodcastEpisodeProcessor,
    private val events: ApplicationEventPublisher,
    @Value("\${app.podcast.transcript-worker.promotion-timeout-hours:24}") promotionTimeoutHours: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val promotionTimeout: Duration = Duration.ofHours(promotionTimeoutHours)

    @Scheduled(fixedDelayString = "\${app.podcast.transcript-worker.interval-ms:120000}",
               initialDelayString = "\${app.podcast.transcript-worker.initial-delay-ms:60000}")
    @SchedulerLock(name = "podcastTranscriptWorker", lockAtMostFor = "10m", lockAtLeastFor = "30s")
    fun tick() {
        val now = Instant.now()
        try {
            processOnePendingTranscript(now)
        } catch (e: Exception) {
            log.error("[PodcastWorker] tick-stap 'transcript' faalde: {}", e.message, e)
        }
        try {
            promoteShowNotesTimeouts(now)
        } catch (e: Exception) {
            log.error("[PodcastWorker] tick-stap 'show-notes-promotie' faalde: {}", e.message, e)
        }
    }

    private fun processOnePendingTranscript(now: Instant) {
        val episode = episodeRepo.findOneReadyForTranscript(now) ?: run {
            log.debug("[PodcastWorker] geen episode klaar voor transcript")
            return
        }
        log.info("[PodcastWorker] pak episode op voor transcript-fase: user='{}' guid={} retryCount={}",
            episode.username, episode.guid, episode.retryCount)
        val result = processor.processTranscript(episode.username, episode.guid)
        when (result) {
            is PodcastEpisodeProcessor.TranscriptResult.Success,
            is PodcastEpisodeProcessor.TranscriptResult.Fatal,
            is PodcastEpisodeProcessor.TranscriptResult.Skipped -> {
                // De processor heeft de status zelf bijgewerkt (DONE,
                // SHOW_NOTES_DONE, ongewijzigd). Niets meer te doen.
            }
            is PodcastEpisodeProcessor.TranscriptResult.RateLimited -> scheduleBackoff(episode, now)
        }
    }

    private fun scheduleBackoff(episode: PodcastEpisode, now: Instant) {
        val newRetryCount = episode.retryCount + 1
        // newRetryCount-1 = aantal failures vóór deze backoff;
        // delay = wachttijd na deze failure. AC #4: 1e poging mislukt → 5m,
        // 2e → 15m, 3e → 45m, 4e+ → 24h.
        val delay = processor.nextRetryDelay(newRetryCount - 1)
        val nextAt = now.plus(delay)
        // Re-load: processor heeft 'm net opgeslagen, we voegen alleen de
        // retry-velden toe (zonder de processor-changes te overschrijven).
        val current = episodeRepo.get(episode.username, episode.guid) ?: return
        episodeRepo.upsert(current.copy(
            retryCount = newRetryCount,
            nextAttemptAt = nextAt
        ))
        log.warn("[PodcastWorker] backoff voor guid={}: retry #{} over {}m (next_attempt_at={})",
            episode.guid, newRetryCount, delay.toMinutes(), nextAt)
    }

    private fun promoteShowNotesTimeouts(now: Instant) {
        val expired = episodeRepo.findShowNotesExpiredForPromotion(now, promotionTimeout)
        if (expired.isEmpty()) return
        log.info("[PodcastWorker] {} episode(s) >{}h op show-notes — feed-promotie triggeren",
            expired.size, promotionTimeout.toHours())
        for (ep in expired) {
            val rid = ep.rssItemId ?: continue
            try {
                events.publishEvent(PodcastPromotionRequested(username = ep.username, rssItemId = rid))
                log.info("[PodcastWorker] show-notes-timeout-promotie getriggerd: user='{}' guid={} rssItemId={}",
                    ep.username, ep.guid, rid)
            } catch (e: Exception) {
                log.warn("[PodcastWorker] kon promotion-event niet publiceren voor guid={}: {}",
                    ep.guid, e.message)
            }
        }
    }
}
