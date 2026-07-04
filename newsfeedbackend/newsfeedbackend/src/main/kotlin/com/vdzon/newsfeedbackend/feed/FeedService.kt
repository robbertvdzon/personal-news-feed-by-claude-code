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

/**
 * Domeinmodel — gaat sinds de DTO-laag NIET meer als JSON over de lijn:
 * de HTTP-responses lopen via [com.vdzon.newsfeedbackend.feed.api.dto.FeedItemDto]
 * en shared/api/dto/SharedFeedItemDto. De vroegere @JsonProperty-workarounds
 * voor `summary`/`isRead`/`isSummary` zijn daarheen verhuisd: dit model
 * wordt nergens meer integraal door Jackson geserialiseerd
 * (FeedItemRepository slaat kolom-per-kolom op; alleen de String-lijsten
 * gaan als jsonb — de kolomdata is dus ongewijzigd van vorm).
 */
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
    val isRead: Boolean = false,
    val starred: Boolean = false,
    val liked: Boolean? = null,
    val createdAt: Instant = Instant.now(),
    val publishedDate: String? = null,
    val isSummary: Boolean = false,
    /**
     * KAN-60: 'ARTICLE' (default) of 'PODCAST'. Discriminator zodat de
     * Feed-tab filter (AC8) op rij-niveau kan filteren zonder cross-join
     * met rss_items. Legacy feed_items uit pre-KAN-60-tijd zijn standaard
     * ARTICLE — veilige default conform refiner-aanname.
     */
    val mediaType: String = "ARTICLE",
    val imageUrl: String? = null
)
