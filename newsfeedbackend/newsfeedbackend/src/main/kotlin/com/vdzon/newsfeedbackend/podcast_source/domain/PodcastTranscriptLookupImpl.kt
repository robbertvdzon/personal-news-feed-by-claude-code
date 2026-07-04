package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.rss.PodcastTranscriptLookup
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import org.springframework.stereotype.Component

@Component
class PodcastTranscriptLookupImpl(
    private val episodeRepo: PodcastEpisodeRepository
) : PodcastTranscriptLookup {
    override fun findTranscriptForRssItem(username: String, rssItemId: String): String? =
        episodeRepo.load(username)
            .firstOrNull { it.rssItemId == rssItemId }
            ?.transcript
            ?.ifBlank { null }
}
