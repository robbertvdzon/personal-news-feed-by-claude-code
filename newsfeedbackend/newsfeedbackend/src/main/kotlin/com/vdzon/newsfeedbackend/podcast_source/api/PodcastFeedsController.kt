package com.vdzon.newsfeedbackend.podcast_source.api

import com.vdzon.newsfeedbackend.common.SecurityHelpers
import com.vdzon.newsfeedbackend.podcast_source.PodcastFeedsService
import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP-laag voor de podcast-feed-lijst. Stond eerst in SettingsController,
 * maar hoort bij podcast_source: het opslaan met validatie en de
 * ingestion-trigger zijn verantwoordelijkheden van deze module en zitten
 * achter [PodcastFeedsService]; settings levert alleen de opslag van de
 * lijst (en het lezen ervan voor de GET hieronder).
 */
@RestController
class PodcastFeedsController(
    private val settings: SettingsService,
    private val podcastFeeds: PodcastFeedsService
) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping("/api/podcast-feeds")
    fun getPodcastFeeds(): PodcastFeedsSettings = settings.getPodcastFeeds(user())

    /**
     * Sla de podcast-feed-lijst op. Validatie van nieuwe feeds en het
     * triggeren van een ingestion-run gebeuren in [PodcastFeedsService].
     */
    @PutMapping("/api/podcast-feeds")
    fun savePodcastFeeds(@RequestBody body: PodcastFeedsSettings): PodcastFeedsSettings =
        podcastFeeds.savePodcastFeeds(user(), body)
}
