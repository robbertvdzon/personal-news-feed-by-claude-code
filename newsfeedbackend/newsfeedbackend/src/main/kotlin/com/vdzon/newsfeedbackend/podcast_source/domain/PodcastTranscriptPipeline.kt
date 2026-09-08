package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.PodcastTranscriptRequested
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

/**
 * SF-1739: event-driven aandrijving van de async transcript-fase.
 *
 * Vervangt de `@Scheduled(fixedDelay=2m)`-tick van de oude
 * `PodcastTranscriptWorker`: die bevroeg de database elke twee minuten
 * ook als er niets te doen was, waardoor Neon nooit kon suspenden.
 * Nu start de verwerking op een [PodcastTranscriptRequested]-event —
 * gepubliceerd door [PodcastShowNotesProcessor] bij de overgang naar
 * `NEEDS_TRANSCRIPT` en door [PodcastRecoveryScheduler] als vangnet.
 *
 * Garanties (ongewijzigd t.o.v. de tick):
 *  - **Max één aflevering tegelijk**: de listener draait op de
 *    single-thread-executor `podcastTranscriptExecutor` én neemt een
 *    proceswijde [ReentrantLock], zodat ook een directe aanroep
 *    (bv. vanuit een test of een toekomstige caller) serieel blijft.
 *  - **Idempotentie**: dubbele events leiden niet tot dubbele
 *    verwerking — er wordt per event opnieuw uit de database gelezen en
 *    alleen `NEEDS_TRANSCRIPT` met een verlopen `next_attempt_at` wordt
 *    opgepakt (de tweede poging ziet DONE/SHOW_NOTES_DONE en skipt).
 *  - **Backoff**: bij een Whisper-rate-limit `retry_count++` en
 *    `next_attempt_at = now + 5m / 15m / 45m / 24h`
 *    ([PodcastTranscriptProcessor.nextRetryDelay]).
 *
 * Bewuste keuze (story SF-1739): er is **geen** in-memory hertrigger op
 * `next_attempt_at`. Een retry start dus bij de eerstvolgende run van de
 * uurlijkse recovery-job (tot ~1 uur later); dat is in de story expliciet
 * als acceptabel benoemd en scheelt een extra timer-mechanisme.
 */
@Component
class PodcastTranscriptPipeline(
    private val episodeRepo: PodcastEpisodeRepository,
    private val processor: PodcastTranscriptProcessor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Proceswijde serialisatie: max één aflevering tegelijk in verwerking. */
    private val lock = ReentrantLock()

    @EventListener
    @Async("podcastTranscriptExecutor")
    fun onTranscriptRequested(event: PodcastTranscriptRequested) {
        handle(event.username, event.guid)
    }

    /**
     * Verwerkt één aflevering, serieel. Publiek zodat de recovery-job en
     * tests 'm ook synchroon kunnen aanroepen zonder de @Async-proxy.
     */
    fun handle(username: String, guid: String) {
        lock.lock()
        MDC.put("username", username)
        try {
            process(username, guid, Instant.now())
        } catch (e: Exception) {
            log.error("[PodcastTranscript] verwerking faalde voor guid={}: {}", guid, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun process(username: String, guid: String, now: Instant) {
        val episode = episodeRepo.get(username, guid) ?: run {
            log.warn("[PodcastTranscript] guid={} niet (meer) in DB — skip", guid)
            return
        }
        if (episode.status != PodcastEpisodeStatus.NEEDS_TRANSCRIPT) {
            log.debug("[PodcastTranscript] guid={} status={} — skip (geen transcriptwerk)", guid, episode.status)
            return
        }
        val nextAttemptAt = episode.nextAttemptAt
        if (nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
            log.debug("[PodcastTranscript] guid={} zit in backoff tot {} — skip", guid, nextAttemptAt)
            return
        }
        log.info("[PodcastTranscript] start transcript-fase: user='{}' guid={} retryCount={}",
            username, guid, episode.retryCount)
        when (processor.processTranscript(username, guid)) {
            is PodcastTranscriptProcessor.TranscriptResult.Success,
            is PodcastTranscriptProcessor.TranscriptResult.Fatal,
            is PodcastTranscriptProcessor.TranscriptResult.Skipped -> {
                // De processor heeft de status zelf bijgewerkt (DONE,
                // SHOW_NOTES_DONE, ongewijzigd). Niets meer te doen.
            }
            is PodcastTranscriptProcessor.TranscriptResult.RateLimited -> scheduleBackoff(episode, now)
        }
    }

    private fun scheduleBackoff(episode: PodcastEpisode, now: Instant) {
        val newRetryCount = episode.retryCount + 1
        // newRetryCount-1 = aantal failures vóór deze backoff;
        // delay = wachttijd na deze failure: 1e poging mislukt → 5m,
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
        log.warn("[PodcastTranscript] backoff voor guid={}: retry #{} over {}m (next_attempt_at={})",
            episode.guid, newRetryCount, delay.toMinutes(), nextAt)
    }
}
