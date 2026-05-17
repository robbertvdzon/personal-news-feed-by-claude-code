package com.vdzon.newsfeedbackend.settings.api

import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import com.vdzon.newsfeedbackend.podcastfeed.InvalidPodcastFeedException
import com.vdzon.newsfeedbackend.podcastfeed.PodcastFeedService
import com.vdzon.newsfeedbackend.podcastfeed.PodcastFeedsSettings
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SettingsController(
    private val service: SettingsService,
    private val podcastFeeds: PodcastFeedService
) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping("/api/settings")
    fun getSettings(): List<CategorySettings> = service.getCategories(user())

    @PutMapping("/api/settings")
    fun saveSettings(@RequestBody body: List<CategorySettings>): List<CategorySettings> =
        service.saveCategories(user(), body)

    @GetMapping("/api/rss-feeds")
    fun getRssFeeds(): RssFeedsSettings = service.getRssFeeds(user())

    @PutMapping("/api/rss-feeds")
    fun saveRssFeeds(@RequestBody body: RssFeedsSettings): RssFeedsSettings =
        service.saveRssFeeds(user(), body)

    // Podcast-bronnen (KAN-54). Patroon analoog aan rss-feeds. PUT
    // valideert nieuwe URLs synchroon en gooit 400 met een leesbare
    // foutmelding (AC7) als de feed niet binnen ~5s ophaalbaar is.
    @GetMapping("/api/podcast-feeds")
    fun getPodcastFeeds(): PodcastFeedsSettings = PodcastFeedsSettings(podcastFeeds.list(user()))

    @PutMapping("/api/podcast-feeds")
    fun savePodcastFeeds(@RequestBody body: PodcastFeedsSettings): PodcastFeedsSettings =
        PodcastFeedsSettings(podcastFeeds.save(user(), body.feeds))

    @PostMapping("/api/podcast-feeds/refresh")
    fun refreshPodcastFeeds(): Map<String, String> {
        podcastFeeds.triggerRefresh(user())
        return mapOf("status" to "ok")
    }

    @ExceptionHandler(InvalidPodcastFeedException::class)
    fun handleInvalidPodcastFeed(e: InvalidPodcastFeedException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            mapOf("error" to (e.message ?: "Kon feed niet ophalen"), "url" to e.url)
        )
}
