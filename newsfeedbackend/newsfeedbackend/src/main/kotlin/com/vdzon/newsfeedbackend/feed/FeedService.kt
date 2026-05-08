package com.vdzon.newsfeedbackend.feed

import java.time.Instant

interface FeedService {
    fun list(username: String): List<FeedItem>
    fun get(username: String, id: String): FeedItem?
    fun save(username: String, item: FeedItem): FeedItem
    fun delete(username: String, id: String): Boolean
    fun setRead(username: String, id: String, read: Boolean): Boolean
    /** Markeer alle items als gelezen. Returnt het aantal items dat is bijgewerkt (was=ongelezen). */
    fun markAllRead(username: String): Int
    fun toggleStar(username: String, id: String): Boolean
    fun setFeedback(username: String, id: String, liked: Boolean?): Boolean
    fun cleanup(username: String, olderThanDays: Int, keepStarred: Boolean, keepLiked: Boolean, keepUnread: Boolean): Int
}

data class FeedItem(
    val id: String,
    /** Originele titel uit de RSS-feed (vaak Engels). */
    val title: String,
    /**
     * Korte Nederlandse titel (max ~70 tekens) — gebruikt op de feed-lijst
     * en als headline op het detail-scherm. Leeg voor legacy items van
     * vóór deze rewrite; frontend moet dan terugvallen op `title`.
     */
    val titleNl: String = "",
    // Explicit @JsonProperty om collision met de Kotlin-getter `isSummary()`
    // van de Boolean `isSummary` (default Jackson-naam ook "summary") te
    // voorkomen — anders dropt Jackson dit veld in de serialisatie.
    @get:com.fasterxml.jackson.annotation.JsonProperty("summary")
    @field:com.fasterxml.jackson.annotation.JsonProperty("summary")
    @param:com.fasterxml.jackson.annotation.JsonProperty("summary")
    /** Uitgebreide Nederlandse samenvatting (400-600 woorden, mag markdown bevatten) — detail-scherm. */
    val summary: String,
    /**
     * Korte Nederlandse samenvatting van 2 regels (~30-50 woorden, **plain
     * text** — geen markdown) — gebruikt onder de titel op de feed-lijst.
     * Leeg voor legacy items.
     */
    val shortSummary: String = "",
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
