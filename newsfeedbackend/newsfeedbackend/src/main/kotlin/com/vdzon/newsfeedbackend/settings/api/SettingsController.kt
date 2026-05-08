package com.vdzon.newsfeedbackend.settings.api

import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SettingsController(private val service: SettingsService) {

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
}
