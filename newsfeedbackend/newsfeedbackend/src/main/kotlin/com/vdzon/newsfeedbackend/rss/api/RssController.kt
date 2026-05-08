package com.vdzon.newsfeedbackend.rss.api

import com.vdzon.newsfeedbackend.common.FeedbackBody
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.RssService
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rss")
class RssController(private val service: RssService) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping
    fun list(): List<RssItem> = service.list(user())

    @PostMapping("/refresh")
    fun refresh(): Map<String, String> {
        service.triggerRefresh(user())
        return mapOf("status" to "ok")
    }

    @DeleteMapping("/cleanup")
    fun cleanup(
        @RequestParam(defaultValue = "30") olderThanDays: Int,
        @RequestParam(defaultValue = "true") keepStarred: Boolean,
        @RequestParam(defaultValue = "true") keepLiked: Boolean,
        @RequestParam(defaultValue = "false") keepUnread: Boolean
    ): Map<String, Int> = mapOf("removed" to service.cleanup(user(), olderThanDays, keepStarred, keepLiked, keepUnread))

    @PutMapping("/{id}/read")
    fun read(@PathVariable id: String): Map<String, String> { service.setRead(user(), id, true); return mapOf("status" to "ok") }

    @PutMapping("/{id}/unread")
    fun unread(@PathVariable id: String): Map<String, String> { service.setRead(user(), id, false); return mapOf("status" to "ok") }

    @PutMapping("/{id}/star")
    fun star(@PathVariable id: String): Map<String, String> { service.toggleStar(user(), id); return mapOf("status" to "ok") }

    @PutMapping("/{id}/feedback")
    fun feedback(@PathVariable id: String, @RequestBody body: FeedbackBody): Map<String, String> {
        service.setFeedback(user(), id, body.liked); return mapOf("status" to "ok")
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Map<String, String> { service.delete(user(), id); return mapOf("status" to "ok") }
}
