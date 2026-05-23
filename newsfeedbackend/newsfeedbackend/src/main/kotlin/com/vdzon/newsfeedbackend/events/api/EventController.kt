package com.vdzon.newsfeedbackend.events.api

import com.vdzon.newsfeedbackend.common.SecurityHelpers
import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.events.EventService
import com.vdzon.newsfeedbackend.events.EventVideo
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/events")
class EventController(
    private val service: EventService
) {
    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping
    fun list(): List<Event> = service.list(user())

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<Event> {
        val ev = service.get(user(), id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ev)
    }

    /**
     * Handmatige trigger vanuit Settings — mirror van POST /api/rss/refresh.
     * Start de event-discovery asynchroon; de response komt direct terug.
     */
    @PostMapping("/discover")
    fun discover(): Map<String, String> {
        service.triggerDiscovery(user())
        return mapOf("status" to "ok")
    }

    /**
     * KAN-66: de ontdekte video's (keynotes/sessies) van één event.
     */
    @GetMapping("/{id}/videos")
    fun videos(@PathVariable id: String): List<EventVideo> = service.listVideos(user(), id)

    /**
     * KAN-66: handmatige trigger vanuit Settings voor de video-zoekjob.
     * Apart van /discover (de event-job). Start asynchroon; de response
     * komt direct terug.
     */
    @PostMapping("/videos/discover")
    fun discoverVideos(): Map<String, String> {
        service.triggerVideoDiscovery(user())
        return mapOf("status" to "ok")
    }

    /**
     * KAN-67: maak (of geef bestaande) Nederlandse samenvatting van één
     * video op aanvraag. Synchroon: de call blijft openstaan tot het
     * transcript is opgehaald (YouTube of Whisper) en Claude een
     * samenvatting heeft geleverd. Een seconden- tot minuten-lange
     * response is normaal — de frontend toont een laad-indicator.
     *
     * Idempotent: een tweede call op dezelfde (event, videoUrl)-combo
     * met een al opgeslagen samenvatting komt direct terug zonder AI-
     * calls.
     *
     * 200 met de bijgewerkte [EventVideo] bij succes.
     * 404 als de video niet (meer) in de DB staat.
     * 502 als zowel YouTube-transcript als Whisper niets opleverde —
     *     gebruiker mag opnieuw proberen (knop blijft staan).
     */
    @PostMapping("/{id}/videos/summarize")
    fun summarizeVideo(
        @PathVariable id: String,
        @RequestBody body: VideoSummaryRequest
    ): ResponseEntity<EventVideo> {
        val url = body.videoUrl.trim()
        if (url.isBlank()) return ResponseEntity.badRequest().build()
        val current = service.listVideos(user(), id).find { it.videoUrl == url }
            ?: return ResponseEntity.notFound().build()
        if (!current.summaryNl.isNullOrBlank()) {
            // Snelle pad: skip de pipeline-lock-overhead bij cache-hit.
            return ResponseEntity.ok(current)
        }
        val updated = service.ensureVideoSummary(user(), id, url)
            ?: return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        return ResponseEntity.ok(updated)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Map<String, String> {
        service.delete(user(), id)
        return mapOf("status" to "ok")
    }
}

/** KAN-67: body voor POST /api/events/{id}/videos/summarize. */
data class VideoSummaryRequest(val videoUrl: String)
