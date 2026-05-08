package com.vdzon.newsfeedbackend.feed

import java.time.Instant

interface FeedService {
    fun list(username: String): List<FeedItem>
    fun get(username: String, id: String): FeedItem?
    fun save(username: String, item: FeedItem): FeedItem
    fun delete(username: String, id: String): Boolean
    fun setRead(username: String, id: String, read: Boolean): Boolean
    fun toggleStar(username: String, id: String): Boolean
    fun setFeedback(username: String, id: String, liked: Boolean?): Boolean
    fun cleanup(username: String, olderThanDays: Int, keepStarred: Boolean, keepLiked: Boolean, keepUnread: Boolean): Int
}

data class FeedItem(
    val id: String,
    val title: String,
    val summary: String,
    val url: String? = null,
    val category: String = "overig",
    val source: String = "",
    val sourceRssIds: List<String> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val feedReason: String = "",
    @get:com.fasterxml.jackson.annotation.JsonProperty("isRead")
    @field:com.fasterxml.jackson.annotation.JsonProperty("isRead")
    @param:com.fasterxml.jackson.annotation.JsonProperty("isRead")
    val isRead: Boolean = false,
    val starred: Boolean = false,
    val liked: Boolean? = null,
    val createdAt: Instant = Instant.now(),
    val publishedDate: String? = null,
    @get:com.fasterxml.jackson.annotation.JsonProperty("isSummary")
    @field:com.fasterxml.jackson.annotation.JsonProperty("isSummary")
    @param:com.fasterxml.jackson.annotation.JsonProperty("isSummary")
    val isSummary: Boolean = false
)
