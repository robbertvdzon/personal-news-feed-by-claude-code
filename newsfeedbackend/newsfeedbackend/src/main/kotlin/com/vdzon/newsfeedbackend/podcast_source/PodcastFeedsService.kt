package com.vdzon.newsfeedbackend.podcast_source

import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings

/**
 * Publieke ingang voor het beheren van de podcast-feed-lijst.
 *
 * Het valideren van nieuwe feed-URLs (de feed één keer proberen op te
 * halen) en het triggeren van de ingestion horen bij deze module; de
 * api-laag delegeert daarom volledig hierheen. Geïmplementeerd in
 * domain/.
 */
interface PodcastFeedsService {
    /**
     * Valideer de nieuwe URLs uit [settings], sla de lijst op en trigger
     * daarna een ingestion-run. Gooit `BadRequestException` als een
     * nieuwe feed niet op te halen is.
     */
    fun savePodcastFeeds(username: String, settings: PodcastFeedsSettings): PodcastFeedsSettings
}
