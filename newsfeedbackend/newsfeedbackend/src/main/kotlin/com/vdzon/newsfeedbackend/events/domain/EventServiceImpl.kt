package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.events.EventService
import com.vdzon.newsfeedbackend.events.EventVideo
import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.events.infrastructure.EventVideoRepository
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
    private val events: ApplicationEventPublisher,
    private val summaryPipeline: EventVideoSummaryPipeline
) : EventService {

    override fun list(username: String): List<Event> = repo.load(username)

    override fun get(username: String, id: String): Event? =
        repo.load(username).find { it.id == id }

    override fun delete(username: String, id: String): Boolean = repo.delete(username, id)

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
