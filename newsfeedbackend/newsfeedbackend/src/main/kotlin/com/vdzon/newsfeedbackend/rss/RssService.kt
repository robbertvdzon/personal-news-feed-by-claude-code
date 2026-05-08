package com.vdzon.newsfeedbackend.rss

import java.time.Instant

interface RssService {
    fun list(username: String): List<RssItem>
    fun get(username: String, id: String): RssItem?
    fun delete(username: String, id: String): Boolean
    fun setRead(username: String, id: String, read: Boolean): Boolean
    /** Markeer alle items als gelezen. Returnt het aantal items dat is bijgewerkt (was=ongelezen). */
    fun markAllRead(username: String): Int
    fun toggleStar(username: String, id: String): Boolean
    fun setFeedback(username: String, id: String, liked: Boolean?): Boolean
    fun cleanup(username: String, olderThanDays: Int, keepStarred: Boolean, keepLiked: Boolean, keepUnread: Boolean): Int
    fun triggerRefresh(username: String)

    /**
     * Re-run only the AI selection step against already-stored RssItems.
     * Skips fetch + per-article summarisation; just calls Claude once
     * with the user's preferences and updates inFeed/feedReason on every
     * stored item. New entries get a generated FeedItem; existing
     * inFeed=true entries keep theirs even if Claude flips them.
     */
    fun triggerReselect(username: String)
}

data class RssItem(
    val id: String,
    val title: String,
    val summary: String = "",
    val url: String,
    val category: String = "overig",
    val feedUrl: String = "",
    val source: String = "",
    val snippet: String = "",
    val publishedDate: String? = null,
    val timestamp: Instant = Instant.now(),
    val processedAt: Instant? = null,
    val inFeed: Boolean = false,
    val feedReason: String = "",
    @get:com.fasterxml.jackson.annotation.JsonProperty("isRead")
    @field:com.fasterxml.jackson.annotation.JsonProperty("isRead")
    @param:com.fasterxml.jackson.annotation.JsonProperty("isRead")
    val isRead: Boolean = false,
    val starred: Boolean = false,
    val liked: Boolean? = null,
    val topics: List<String> = emptyList(),
    val feedItemId: String? = null
)
