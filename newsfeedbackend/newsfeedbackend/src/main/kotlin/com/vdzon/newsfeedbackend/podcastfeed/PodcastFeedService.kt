package com.vdzon.newsfeedbackend.podcastfeed

import java.time.Instant

/**
 * Beheer van per-user podcast-bronnen (KAN-54). Een PodcastFeed is een
 * abonnement op een podcast-RSS; de pipeline haalt regelmatig nieuwe
 * afleveringen op, transcribeert (optioneel) en levert ze als
 * [com.vdzon.newsfeedbackend.feed.FeedItem] met `kind = "podcast"` af in
 * de bestaande feed-tab.
 */
interface PodcastFeedService {
    fun list(username: String): List<PodcastFeed>
    /**
     * Persisteer de volledige set podcast-bronnen voor een user. Nieuwe
     * URLs worden synchroon gevalideerd (5s timeout) zodat de UI binnen
     * 10s een duidelijke foutmelding kan tonen (AC7). Bij een failed
     * URL wordt een [InvalidPodcastFeedException] gegooid en is er niets
     * opgeslagen.
     */
    fun save(username: String, feeds: List<PodcastFeed>): List<PodcastFeed>
    fun delete(username: String, url: String): Boolean
    fun triggerRefresh(username: String)
    fun listEpisodes(username: String): List<PodcastEpisode>
    /**
     * Zet alle episodes die middenin de pipeline hangen (DOWNLOADING /
     * TRANSCRIBING / SUMMARIZING) na een pod-restart op FAILED. Voorkomt
     * dubbele Whisper-kosten en duplicate FeedItems bij re-processing.
     */
    fun resetStuck(): Int
}

data class PodcastFeed(
    val url: String,
    val transcribeEnabled: Boolean = true
)

data class PodcastFeedsSettings(val feeds: List<PodcastFeed> = emptyList())

enum class EpisodeStatus { PENDING, DOWNLOADING, TRANSCRIBING, SUMMARIZING, DONE, FAILED }

data class PodcastEpisode(
    val guid: String,
    val feedUrl: String,
    val title: String = "",
    val podcastName: String = "",
    val audioUrl: String = "",
    val durationSeconds: Int? = null,
    val description: String = "",
    val publishedDate: String? = null,
    val transcript: String? = null,
    val summary: String? = null,
    /** "transcript" | "show_notes" — bron waarop de samenvatting is gemaakt. */
    val summarySource: String? = null,
    val status: EpisodeStatus = EpisodeStatus.PENDING,
    val errorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
    val processedAt: Instant? = null,
    val feedItemId: String? = null
)

class InvalidPodcastFeedException(val url: String, message: String) : RuntimeException(message)
