package com.vdzon.newsfeedbackend.feed.api

import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.common.FeedbackBody
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
@RequestMapping("/api/feed")
class FeedController(private val service: FeedService) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping
    fun list(): List<FeedItem> = service.list(user())

    @DeleteMapping("/cleanup")
    fun cleanup(
        @RequestParam(defaultValue = "30") olderThanDays: Int,
        @RequestParam(defaultValue = "true") keepStarred: Boolean,
        @RequestParam(defaultValue = "true") keepLiked: Boolean,
        @RequestParam(defaultValue = "false") keepUnread: Boolean
    ): Map<String, Int> = mapOf("removed" to service.cleanup(user(), olderThanDays, keepStarred, keepLiked, keepUnread))

    @PutMapping("/{id}/read")
    fun markRead(@PathVariable id: String): Map<String, String> {
        service.setRead(user(), id, true); return mapOf("status" to "ok")
    }

    @PutMapping("/{id}/unread")
    fun markUnread(@PathVariable id: String): Map<String, String> {
        service.setRead(user(), id, false); return mapOf("status" to "ok")
    }

    @PostMapping("/markAllRead")
    fun markAllRead(): Map<String, Int> = mapOf("updated" to service.markAllRead(user()))

    @PutMapping("/{id}/star")
    fun toggleStar(@PathVariable id: String): Map<String, String> {
        service.toggleStar(user(), id); return mapOf("status" to "ok")
    }

    @PutMapping("/{id}/feedback")
    fun feedback(@PathVariable id: String, @RequestBody body: FeedbackBody): Map<String, String> {
        service.setFeedback(user(), id, body.liked); return mapOf("status" to "ok")
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Map<String, String> {
        service.delete(user(), id); return mapOf("status" to "ok")
    }
}
