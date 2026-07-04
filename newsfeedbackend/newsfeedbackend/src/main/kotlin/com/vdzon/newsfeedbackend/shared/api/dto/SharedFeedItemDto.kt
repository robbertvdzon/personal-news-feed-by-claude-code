package com.vdzon.newsfeedbackend.shared.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.vdzon.newsfeedbackend.feed.FeedItem
import java.time.Instant

/**
 * Response-DTO voor het publieke `GET /api/shared/feed`.
 *
 * Bewust ZONDER de persoonlijke vlaggen `isRead`/`starred`/`liked` van de
 * bron-gebruiker: die werden voorheen met een copy-met-vaste-waardes-truc
 * op false/null gezet; door ze helemaal weg te laten kan het leesgedrag
 * per constructie niet meer lekken. Dat mag omdat:
 * - de reader-app (frontend-reader/lib/models.dart, FeedItem.fromJson)
 *   deze velden niet parseert — read/star houdt hij lokaal bij;
 * - SharedFeedE2eTest de vlaggen als "missing of false/null" accepteert.
 */
data class SharedFeedItemDto(
    val id: String,
    val title: String,
    val titleNl: String,
    // Zelfde collision-workaround als in FeedItemDto: zonder expliciete
    // naam botst de getter isSummary() (default Jackson-naam "summary")
    // met dit String-veld en dropt Jackson er één.
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
    val createdAt: Instant,
    val publishedDate: String?,
    @get:JsonProperty("isSummary")
    @field:JsonProperty("isSummary")
    @param:JsonProperty("isSummary")
    val isSummary: Boolean,
    val mediaType: String,
    val imageUrl: String?
)

fun FeedItem.toSharedDto(): SharedFeedItemDto = SharedFeedItemDto(
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
    createdAt = createdAt,
    publishedDate = publishedDate,
    isSummary = isSummary,
    mediaType = mediaType,
    imageUrl = imageUrl
)
