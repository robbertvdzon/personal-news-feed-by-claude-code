package com.vdzon.newsfeedbackend.settings.api

import com.vdzon.newsfeedbackend.podcast_source.PodcastIngestionTrigger
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastFeedFetcher
import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings
import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class SettingsController(
    private val service: SettingsService,
    private val podcastTrigger: PodcastIngestionTrigger,
    private val podcastFetcher: PodcastFeedFetcher
) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping("/api/settings")
    fun getSettings(): List<CategorySettings> = service.getCategories(user())

    @PutMapping("/api/settings")
    fun saveSettings(@RequestBody body: List<CategorySettings>): List<CategorySettings> =
        service.saveCategories(user(), body)

    @GetMapping("/api/rss-feeds")
    fun getRssFeeds(): RssFeedsSettings = service.getRssFeeds(user())

    @PutMapping("/api/rss-feeds")
    fun saveRssFeeds(@RequestBody body: RssFeedsSettings): RssFeedsSettings =
        service.saveRssFeeds(user(), body)

    @GetMapping("/api/podcast-feeds")
    fun getPodcastFeeds(): PodcastFeedsSettings = service.getPodcastFeeds(user())

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
        val existing = service.getPodcastFeeds(u).feeds.map { it.url }.toSet()
        val newUrls = body.feeds.map { it.url }.filter { it.isNotBlank() && it !in existing }
        for (url in newUrls) {
            val fetch = podcastFetcher.fetch(url, u)
            if (!fetch.ok) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Kon feed niet ophalen: $url (${fetch.errorMessage ?: "onbekende fout"})"
                )
            }
        }
        val saved = service.savePodcastFeeds(u, body)
        podcastTrigger.trigger(u)
        return saved
    }
}
