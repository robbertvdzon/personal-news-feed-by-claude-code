package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.PodcastIngestionTrigger
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastFeedFetcher
import com.vdzon.newsfeedbackend.rss.RssRefreshRequested
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
 *   2. Pak alleen de 7 nieuwste afleveringen uit de feed-snapshot
 *      (sorteren op `publishedDate` DESC, dan `take(7)`).
 *   3. Filter op nieuwe GUIDs (niet eerder verwerkt → idempotency-cache
 *      op `(username, guid)`).
 *   4. Voor elke écht nieuwe aflevering: PENDING-rij schrijven en
 *      `processEpisode` async kicken.
 *
 * De combinatie 2+3 dekt zowel AC #9 (cap op 7 bij ingestie van een
 * feed van 2000 items om Whisper-credit niet op te branden) als AC #6
 * (3× refresh achter elkaar → 0 extra Whisper-runs): de top-7-window
 * over de feed bevat na de eerste ingestie alleen al-bekende GUIDs, dus
 * een quick-refresh produceert geen nieuwe pipeline-runs. Pas wanneer
 * de podcast werkelijk een nieuwe aflevering publiceert (die schuift de
 * top-7-window op) komt er één in de pipeline.
 *
 * Stap 4 spawnt async-tasks naar een aparte processor-bean
 * ([PodcastShowNotesProcessor]) zodat het Spring-@Async-proxy-mechanisme
 * werkt — een interne `this.process(...)` zou de proxy omzeilen.
 */
@Component
class PodcastIngestionPipeline(
    private val settings: SettingsService,
    private val fetcher: PodcastFeedFetcher,
    private val episodeRepo: PodcastEpisodeRepository,
    private val processor: PodcastShowNotesProcessor,
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
        val fetched = fetcher.fetch(feedUrl, username)
        if (!fetched.ok) {
            log.warn("[PodcastIngest] kon feed niet ophalen: {} ({})", feedUrl, fetched.errorMessage)
            return
        }
        val knownGuids = episodeRepo.existingGuids(username, feedUrl)
        // Pak op elke run alleen de 7 nieuwste afleveringen uit de
        // feed — dat is de bovenkant van de window die we überhaupt
        // willen verwerken. Sorteren op `publishedDate` DESC met "" als
        // lege-fallback houdt de relatieve volgorde van datumloze items
        // stabiel (de feed-volgorde, die Rome al newest-first oplevert).
        // AC #9: cap op 7 bij een feed van 2000 items. AC #6: bij een
        // refresh zonder écht nieuwe aflevering staan in deze top-7 al
        // bekende GUIDs → `novel` is leeg → geen Whisper-runs.
        val topNewest = fetched.episodes
            .sortedByDescending { it.publishedDate ?: "" }
            .take(7)
        val targets = topNewest.filter { it.guid !in knownGuids }
        if (targets.isEmpty()) {
            log.info("[PodcastIngest] geen nieuwe afleveringen voor {} (known={}, fetched={}, window=top-7)",
                feedUrl, knownGuids.size, fetched.episodes.size)
            return
        }
        log.info("[PodcastIngest] {} nieuwe afleveringen voor {} (top-7-window, known={})",
            targets.size, feedUrl, knownGuids.size)
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
            // KAN-60: alleen de snelle show-notes-fase op het kritieke
            // pad. De transcript-fase loopt asynchroon via de
            // PodcastTranscriptWorker (max 1 episode per tick) zodat een
            // burst-feed niet meteen Whisper-rate-limit raakt.
            processor.processShowNotes(username, entry.guid, transcribeEnabled)
        }
    }

    data class PodcastIngestionRequested(val username: String)
}
