package com.vdzon.newsfeedbackend.settings

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

/**
 * KAN-56: per-user lijst met podcast-RSS-bronnen.
 * Per bron toggle voor "transcriberen aan/uit": wanneer uit, valt de
 * pipeline terug op de show-notes (de RSS-`description`) als input voor
 * Claude, zonder Whisper-kosten te maken.
 */
data class PodcastFeedsSettings(val feeds: List<PodcastFeed> = emptyList())

data class PodcastFeed(
    val url: String,
    val transcribeEnabled: Boolean = true
)
