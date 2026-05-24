package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.events.EventService
import com.vdzon.newsfeedbackend.events.EventVideo
import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.events.infrastructure.EventVideoRepository
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/** Async-trigger voor de events-discovery. Mirror van [com.vdzon.newsfeedbackend.rss.domain.RssRefreshRequested]. */
data class EventDiscoveryRequested(val username: String)

/** KAN-66: async-trigger voor de per-event video-discovery. */
data class EventVideoDiscoveryRequested(val username: String)

@Service
class EventServiceImpl(
    private val repo: EventRepository,
    private val videoRepo: EventVideoRepository,
    private val feed: FeedService,
    private val settings: SettingsService,
    private val events: ApplicationEventPublisher,
    private val summaryPipeline: EventVideoSummaryPipeline
) : EventService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun list(username: String): List<Event> = repo.load(username)

    override fun get(username: String, id: String): Event? =
        repo.load(username).find { it.id == id }

    /**
     * KAN-68: bij verwijdering ruimen we (1) het gekoppelde
     * aankondigings-FeedItem op zodat het event ook uit de feed
     * verdwijnt en (2) zetten het event op de denylist (per user)
     * zodat de eerstvolgende discovery-run het niet opnieuw
     * aanmaakt. events.feed_item_id is geen DB-FK, dus dit moet
     * expliciet op service-niveau.
     */
    override fun delete(username: String, id: String): Boolean {
        val current = repo.load(username).find { it.id == id }
        if (current == null) {
            log.info("[Events] delete: event '{}' niet gevonden voor '{}'", id, username)
            return false
        }
        current.feedItemId?.let { feedItemId ->
            val removed = feed.delete(username, feedItemId)
            log.info("[Events] feed-item {} voor event '{}' verwijderd: {}", feedItemId, id, removed)
        }
        val ok = repo.delete(username, id)
        if (ok) {
            settings.addEventToDenylist(username, id, current.name)
            log.info("[Events] event '{}' verwijderd + op denylist voor '{}'", id, username)
        }
        return ok
    }

    override fun triggerDiscovery(username: String) {
        events.publishEvent(EventDiscoveryRequested(username))
    }

    override fun listVideos(username: String, eventId: String): List<EventVideo> =
        videoRepo.loadForEvent(username, eventId)

    override fun triggerVideoDiscovery(username: String) {
        events.publishEvent(EventVideoDiscoveryRequested(username))
    }

    override fun ensureVideoSummary(username: String, eventId: String, videoUrl: String): EventVideo? =
        summaryPipeline.ensureSummary(username, eventId, videoUrl)
}
