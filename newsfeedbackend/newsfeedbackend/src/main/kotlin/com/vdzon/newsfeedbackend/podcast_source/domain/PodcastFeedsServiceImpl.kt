package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.podcast_source.PodcastFeedsService
import com.vdzon.newsfeedbackend.podcast_source.PodcastIngestionTrigger
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastFeedFetcher
import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.springframework.stereotype.Component

/**
 * Valideert nieuwe podcast-feeds, slaat de lijst op en trigger de
 * ingestion — in die volgorde.
 *
 * Alleen NIEUWE URLs (die nog niet in de opgeslagen lijst staan) worden
 * synchroon getoetst door de feed één keer op te halen. Bij een fout
 * volgt een [BadRequestException], die de GlobalExceptionHandler naar
 * HTTP 400 met een Nederlandse foutmelding vertaalt, zodat de gebruiker
 * binnen ~10s ziet dat de URL niet werkt (AC #7). Bestaande URLs worden
 * niet opnieuw opgehaald — die heeft de gebruiker eerder al gevalideerd
 * door 'm toe te voegen.
 */
@Component
class PodcastFeedsServiceImpl(
    private val settingsService: SettingsService,
    private val trigger: PodcastIngestionTrigger,
    private val fetcher: PodcastFeedFetcher
) : PodcastFeedsService {

    override fun savePodcastFeeds(username: String, settings: PodcastFeedsSettings): PodcastFeedsSettings {
        val existing = settingsService.getPodcastFeeds(username).feeds.map { it.url }.toSet()
        val newUrls = settings.feeds.map { it.url }.filter { it.isNotBlank() && it !in existing }
        for (url in newUrls) {
            val fetch = fetcher.fetch(url, username)
            if (!fetch.ok) {
                throw BadRequestException(
                    "Kon feed niet ophalen: $url (${fetch.errorMessage ?: "onbekende fout"})"
                )
            }
        }
        val saved = settingsService.savePodcastFeeds(username, settings)
        trigger.trigger(username)
        return saved
    }
}
