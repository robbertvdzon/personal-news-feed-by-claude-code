package com.vdzon.newsfeedbackend.feed.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.vdzon.newsfeedbackend.feed.FeedItem
import java.time.Instant

/**
 * Response-DTO voor `GET /api/feed`. Veldnamen zijn 1-op-1 het bestaande
 * API-contract (specs/openapi.yaml, bewaakt door de e2e-suite) — het
 * domeinmodel [FeedItem] gaat niet langer zelf over de lijn.
 *
 * De expliciete @JsonProperty's zijn hierheen verhuisd vanaf het
 * domeinmodel: Kotlin-getters `isRead()`/`isSummary()` zouden door
 * Jackson anders als "read"/"summary" geserialiseerd worden, waarbij
 * "summary" bovendien botst met het String-veld [summary] (Jackson
 * dropt het veld dan). Zie de e2e-tests die op `isRead`/`isSummary`
 * asserten (FeedE2eTest, SharedFeedE2eTest).
 */
data class FeedItemDto(
    val id: String,
    val title: String,
    val titleNl: String,
    @get:JsonProperty("summary")
    @field:JsonProperty("summary")
    @param:JsonProperty("summary")
    val summary: String,
    val shortSummary: String,
    val url: String?,
    val category: String,
    val source: String,
    val sourceRssIds: List<String>,
    val sourceUrls: List<String>,
    val topics: List<String>,
    val feedReason: String,
    @get:JsonProperty("isRead")
    @field:JsonProperty("isRead")
    @param:JsonProperty("isRead")
    val isRead: Boolean,
    val starred: Boolean,
    val liked: Boolean?,
    val createdAt: Instant,
    val publishedDate: String?,
    @get:JsonProperty("isSummary")
    @field:JsonProperty("isSummary")
    @param:JsonProperty("isSummary")
    val isSummary: Boolean,
    val mediaType: String,
    val imageUrl: String?
)

fun FeedItem.toDto(): FeedItemDto = FeedItemDto(
    id = id,
    title = title,
    titleNl = titleNl,
    summary = summary,
    shortSummary = shortSummary,
    url = url,
    category = category,
    source = source,
    sourceRssIds = sourceRssIds,
    sourceUrls = sourceUrls,
    topics = topics,
    feedReason = feedReason,
    isRead = isRead,
    starred = starred,
    liked = liked,
    createdAt = createdAt,
    publishedDate = publishedDate,
    isSummary = isSummary,
    mediaType = mediaType,
    imageUrl = imageUrl
)
