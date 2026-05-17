package com.vdzon.newsfeedbackend.podcast_feeds

data class PodcastFeedsSettings(
    val feeds: List<Feed> = emptyList()
) {
    data class Feed(
        val url: String,
        val transcribeEnabled: Boolean = true
    )
}
