package com.vdzon.newsfeedbackend.podcast_feeds

import java.time.Instant

data class PodcastEpisode(
    val guid: String,
    val feedUrl: String,
    val title: String,
    val description: String = "",
    val status: String = "PENDING", // PENDING, DOWNLOADING, TRANSCRIBING, SUMMARIZING, DONE
    val podcastUrl: String? = null,
    val transcript: String? = null,
    val showNotes: String? = null,
    val feedItemId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val errorMessage: String? = null
)
