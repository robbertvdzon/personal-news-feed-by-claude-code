package com.vdzon.newsfeedbackend.settings

interface SettingsService {
    fun getCategories(username: String): List<CategorySettings>
    fun saveCategories(username: String, categories: List<CategorySettings>): List<CategorySettings>

    fun getRssFeeds(username: String): RssFeedsSettings
    fun saveRssFeeds(username: String, settings: RssFeedsSettings): RssFeedsSettings
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
