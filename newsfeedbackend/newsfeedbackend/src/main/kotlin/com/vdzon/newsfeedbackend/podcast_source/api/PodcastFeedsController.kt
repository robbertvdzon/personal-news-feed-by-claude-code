package com.vdzon.newsfeedbackend.podcast_source.api

import com.vdzon.newsfeedbackend.common.SecurityHelpers
import com.vdzon.newsfeedbackend.podcast_source.PodcastIngestionTrigger
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastFeedFetcher
import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Beheer van de podcast-feed-lijst. Stond eerst in SettingsController,
 * maar hoort bij podcast_source: de validatie (feed proberen op te
 * halen) en de ingestion-trigger zijn verantwoordelijkheden van deze
 * module — settings levert alleen de opslag van de lijst.
 */
@RestController
class PodcastFeedsController(
    private val settings: SettingsService,
    private val trigger: PodcastIngestionTrigger,
    private val fetcher: PodcastFeedFetcher
) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping("/api/podcast-feeds")
    fun getPodcastFeeds(): PodcastFeedsSettings = settings.getPodcastFeeds(user())

    /**
     * Sla de podcast-feed-lijst op en trigger meteen een ingestion-run.
     *
     * Validatie: alleen NIEUWE URLs (die nog niet in de bestaande lijst
     * staan) worden synchroon getoetst door de feed één keer op te halen.
     * Bij een fout krijgt de frontend HTTP 400 met een Nederlandse
     * foutmelding zodat de gebruiker binnen ~10s ziet dat de URL niet
     * werkt (AC #7). Bestaande URLs worden niet herzogen — die heeft
     * de gebruiker eerder al gevalideerd door 'm toe te voegen.
     */
    @PutMapping("/api/podcast-feeds")
    fun savePodcastFeeds(@RequestBody body: PodcastFeedsSettings): PodcastFeedsSettings {
        val u = user()
        val existing = settings.getPodcastFeeds(u).feeds.map { it.url }.toSet()
        val newUrls = body.feeds.map { it.url }.filter { it.isNotBlank() && it !in existing }
        for (url in newUrls) {
            val fetch = fetcher.fetch(url, u)
            if (!fetch.ok) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Kon feed niet ophalen: $url (${fetch.errorMessage ?: "onbekende fout"})"
                )
            }
        }
        val saved = settings.savePodcastFeeds(u, body)
        trigger.trigger(u)
        return saved
    }
}
