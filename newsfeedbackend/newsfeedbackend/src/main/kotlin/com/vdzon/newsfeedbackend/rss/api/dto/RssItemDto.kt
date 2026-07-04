package com.vdzon.newsfeedbackend.rss.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.vdzon.newsfeedbackend.rss.RssItem
import java.time.Instant

/**
 * Response-DTO voor `GET /api/rss`. Veldnamen zijn 1-op-1 het bestaande
 * API-contract (specs/openapi.yaml, bewaakt door de e2e-suite) — het
 * domeinmodel [RssItem] gaat niet langer zelf over de lijn.
 *
 * De expliciete @JsonProperty op [isRead] is hierheen verhuisd vanaf het
 * domeinmodel: de Kotlin-getter `isRead()` zou door Jackson anders als
 * "read" geserialiseerd worden.
 *
 * NB: het ruwe transcript zit bewust NIET in dit DTO — dat gaat via het
 * aparte `GET /api/rss/{id}/transcript`-endpoint ([RssTranscriptDto])
 * zodat de feed-listing niet opblaast (zie RssController.transcript).
 */
data class RssItemDto(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val category: String,
    val feedUrl: String,
    val source: String,
    val snippet: String,
    val publishedDate: String?,
    val timestamp: Instant,
    val processedAt: Instant?,
    val inFeed: Boolean,
    val feedReason: String,
    @get:JsonProperty("isRead")
    @field:JsonProperty("isRead")
    @param:JsonProperty("isRead")
    val isRead: Boolean,
    val starred: Boolean,
    val liked: Boolean?,
    val topics: List<String>,
    val feedItemId: String?,
    val mediaType: String,
    val audioUrl: String,
    val durationSeconds: Int?,
    val summarySource: String,
    val longSummary: String?,
    val keyTakeaways: List<String>,
    val imageUrl: String?
)

fun RssItem.toDto(): RssItemDto = RssItemDto(
    id = id,
    title = title,
    summary = summary,
    url = url,
    category = category,
    feedUrl = feedUrl,
    source = source,
    snippet = snippet,
    publishedDate = publishedDate,
    timestamp = timestamp,
    processedAt = processedAt,
    inFeed = inFeed,
    feedReason = feedReason,
    isRead = isRead,
    starred = starred,
    liked = liked,
    topics = topics,
    feedItemId = feedItemId,
    mediaType = mediaType,
    audioUrl = audioUrl,
    durationSeconds = durationSeconds,
    summarySource = summarySource,
    longSummary = longSummary,
    keyTakeaways = keyTakeaways,
    imageUrl = imageUrl
)

/**
 * Response van `GET /api/rss/{id}/transcript` (KAN-62). Zelfde JSON-vorm
 * als de eerdere `Map("transcript" to ...)`: `{"transcript": "..."}`.
 */
data class RssTranscriptDto(
    val transcript: String
)
