package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.PodcastIngestionTrigger
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastFeedFetcher
import com.vdzon.newsfeedbackend.rss.domain.RssRefreshRequested
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Koppelt de podcast-ingestion aan dezelfde 'refresh'-trigger als de
 * RSS-pipeline. Verloop per gebruiker:
 *
 *   1. Voor elke geconfigureerde podcast-feed: fetch RSS.
 *   2. Filter op nieuwe GUIDs (niet eerder verwerkt → idempotency).
 *   3. Bij de eerste ingestie van een feed: cap op de laatste 7
 *      afleveringen (AC #9 — voorkomt dat een feed van 2000 episodes
 *      Whisper-credit opbrandt).
 *   4. Voor elke nieuwe aflevering: PENDING-rij schrijven en
 *      `processEpisode` async kicken.
 *
 *   Stap 4 spawnt async-tasks naar een aparte processor-bean
 *   ([PodcastEpisodeProcessor]) zodat het Spring-@Async-proxy-mechanisme
 *   werkt — een interne `this.process(...)` zou de proxy omzeilen.
 */
@Component
class PodcastIngestionPipeline(
    private val settings: SettingsService,
    private val fetcher: PodcastFeedFetcher,
    private val episodeRepo: PodcastEpisodeRepository,
    private val processor: PodcastEpisodeProcessor,
    private val events: ApplicationEventPublisher
) : PodcastIngestionTrigger {

    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    override fun trigger(username: String) {
        events.publishEvent(PodcastIngestionRequested(username))
    }

    /**
     * Luistert op het bestaande RSS-refresh-event (zodat de bestaande
     * "Refresh"-knop in de UI ook podcasts ophaalt; aanname [REFINER]).
     * Apart luisteren op een eigen event laat ons ook stand-alone
     * triggeren vanuit de SettingsController na 'opslaan'.
     */
    @EventListener
    @Async
    fun onRssRefresh(event: RssRefreshRequested) {
        run(event.username)
    }

    @EventListener
    @Async
    fun onPodcastTrigger(event: PodcastIngestionRequested) {
        run(event.username)
    }

    fun run(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[PodcastIngest] al een run actief voor '{}', skip overlap", username)
            return
        }
        MDC.put("username", username)
        try {
            val feeds = settings.getPodcastFeeds(username).feeds
            if (feeds.isEmpty()) {
                log.debug("[PodcastIngest] geen podcast-feeds voor '{}'", username)
                return
            }
            log.info("[PodcastIngest] start voor '{}' — {} feeds", username, feeds.size)
            for (feed in feeds) {
                try {
                    ingestFeed(username, feed.url, feed.transcribeEnabled)
                } catch (e: Exception) {
                    log.warn("[PodcastIngest] feed-error {}: {}", feed.url, e.message)
                }
            }
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun ingestFeed(username: String, feedUrl: String, transcribeEnabled: Boolean) {
        val isFirstIngestion = episodeRepo.countForFeed(username, feedUrl) == 0
        val fetched = fetcher.fetch(feedUrl, username)
        if (!fetched.ok) {
            log.warn("[PodcastIngest] kon feed niet ophalen: {} ({})", feedUrl, fetched.errorMessage)
            return
        }
        val knownGuids = episodeRepo.existingGuids(username, feedUrl)
        val novel = fetched.episodes.filter { it.guid !in knownGuids }
        // AC #9: bij de allereerste ingestie cappen op de 7 nieuwste.
        // SyndFeed levert entries doorgaans newest-first; we vertrouwen
        // die volgorde — bij twijfel sorteert de feed-volgorde altijd
        // beter dan willekeurig 7 pakken.
        val targets = if (isFirstIngestion) novel.take(7) else novel
        if (targets.isEmpty()) {
            log.info("[PodcastIngest] geen nieuwe afleveringen voor {} (known={}, fetched={})",
                feedUrl, knownGuids.size, fetched.episodes.size)
            return
        }
        log.info("[PodcastIngest] {} nieuwe afleveringen voor {} (firstIngest={})",
            targets.size, feedUrl, isFirstIngestion)
        for (entry in targets) {
            val ep = PodcastEpisode(
                username = username,
                guid = entry.guid,
                feedUrl = feedUrl,
                podcastName = fetched.podcastName,
                title = entry.title,
                audioUrl = entry.audioUrl,
                durationSeconds = entry.durationSeconds,
                publishedDate = entry.publishedDate,
                showNotes = entry.showNotes,
                status = PodcastEpisodeStatus.PENDING
            )
            episodeRepo.upsert(ep)
            processor.process(username, entry.guid, transcribeEnabled)
        }
    }

    data class PodcastIngestionRequested(val username: String)
}
