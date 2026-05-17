package com.vdzon.newsfeedbackend.settings

import com.vdzon.newsfeedbackend.podcast_feeds.PodcastFeedsSettings

interface SettingsService {
    fun getCategories(username: String): List<CategorySettings>
    fun saveCategories(username: String, categories: List<CategorySettings>): List<CategorySettings>

    fun getRssFeeds(username: String): RssFeedsSettings
    fun saveRssFeeds(username: String, settings: RssFeedsSettings): RssFeedsSettings

    fun getPodcastFeeds(username: String): PodcastFeedsSettings
    fun savePodcastFeeds(username: String, settings: PodcastFeedsSettings): PodcastFeedsSettings
}

data class CategorySettings(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val extraInstructions: String = "",
    @get:com.fasterxml.jackson.annotation.JsonProperty("isSystem")
    @field:com.fasterxml.jackson.annotation.JsonProperty("isSystem")
    @param:com.fasterxml.jackson.annotation.JsonProperty("isSystem")
    val isSystem: Boolean = false
)

data class RssFeedsSettings(val feeds: List<String> = emptyList())
