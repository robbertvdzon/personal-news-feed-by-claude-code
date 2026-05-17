package com.vdzon.newsfeedbackend.podcast_source

import java.time.Instant

/**
 * Tracking-rij voor één podcast-aflevering tijdens de Whisper+Claude-
 * pipeline. PK (username, guid) is meteen de idempotency-cache: een
 * refresh die dezelfde GUID opnieuw tegenkomt verwerkt 'm niet opnieuw.
 *
 * Status-pipeline: PENDING → DOWNLOADING → TRANSCRIBING → SUMMARIZING
 * → DONE, met FAILED als terminale-fout-state. Bij DONE staat
 * [rssItemId] gevuld zodat het bijbehorende rss_items-kaartje
 * gevonden kan worden zonder dubbele inserts.
 */
data class PodcastEpisode(
    val username: String,
    val guid: String,
    val feedUrl: String,
    val podcastName: String = "",
    val title: String = "",
    val audioUrl: String = "",
    val durationSeconds: Int? = null,
    val publishedDate: String? = null,
    val showNotes: String = "",
    val transcript: String = "",
    val summary: String = "",
    val status: PodcastEpisodeStatus = PodcastEpisodeStatus.PENDING,
    val errorMessage: String? = null,
    val rssItemId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class PodcastEpisodeStatus {
    PENDING,
    DOWNLOADING,
    TRANSCRIBING,
    SUMMARIZING,
    DONE,
    FAILED
}
