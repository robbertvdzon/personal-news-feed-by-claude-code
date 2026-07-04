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

    /**
     * Voeg een rss-item toe of werk het bij. Publieke API zodat andere
     * modules (podcast_source schrijft podcast-cards) niet aan de
     * repository hoeven.
     */
    fun upsert(username: String, item: RssItem): RssItem
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
    val feedItemId: String? = null,
    /**
     * KAN-56: discriminator voor de RSS-tab. "ARTICLE" voor klassieke
     * RSS-artikelen (default voor legacy rows), "PODCAST" voor podcast-
     * afleveringen ge-ingest via de Whisper+Claude-pipeline.
     */
    val mediaType: String = "ARTICLE",
    /** Bij PODCAST: directe MP3-URL zodat de gebruiker het origineel kan afspelen. */
    val audioUrl: String = "",
    /** Bij PODCAST: lengte in seconden uit de feed (itunes:duration). Null als onbekend. */
    val durationSeconds: Int? = null,
    /**
     * KAN-60: 'transcript' (default) of 'show_notes'. Bij 'show_notes' is
     * de samenvatting nog op de RSS-description gebaseerd en moet de
     * frontend een voorlopige-badge tonen. Wordt overschreven door
     * 'transcript' zodra de async transcript-fase klaar is.
     */
    val summarySource: String = "transcript",
    /**
     * KAN-62: lange Nederlandse samenvatting voor het podcast-detail-
     * scherm (3-5 alinea's, ~400-600 woorden). `null` voor (a) niet-
     * podcast cards en (b) podcast cards die nog niet door de
     * uitgebreide Claude-prompt heen zijn. De Flutter-app valt in dat
     * geval terug op [summary]. Niet gezet voor `mediaType=ARTICLE`.
     */
    val longSummary: String? = null,
    /**
     * KAN-62: 5-10 concrete takeaway-bullets uit de podcast-aflevering.
     * Eén regel per bullet, geen sub-bullets, geen markdown-headers.
     * Lege lijst = sectie wordt verborgen op het detail-scherm.
     */
    val keyTakeaways: List<String> = emptyList(),
    val imageUrl: String? = null
)
