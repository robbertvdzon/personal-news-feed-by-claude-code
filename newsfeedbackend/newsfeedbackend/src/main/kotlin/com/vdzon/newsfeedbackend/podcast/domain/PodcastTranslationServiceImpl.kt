package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.podcast.EpisodeLookup
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.PodcastTranslationService
import com.vdzon.newsfeedbackend.podcast.TranslationStart
import com.vdzon.newsfeedbackend.podcast.TtsProvider
import com.vdzon.newsfeedbackend.podcast.infrastructure.PodcastRepository
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeLookup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * KAN-63: orchestreert de "vertaal & genereer NL-podcast"-flow.
 *
 * - [lookup] doet een goedkope projection-query door de episode-list
 *   één keer te laden (zoals [com.vdzon.newsfeedbackend.podcast_source.domain.PodcastTranscriptLookupImpl]
 *   ook doet). De feed-listing per user is klein genoeg om dit zonder
 *   index over `rss_item_id` te doen.
 * - [startTranslation] is idempotent: bestaat er al een vertaling (DONE
 *   of in progress) voor de gegeven episodeGuid, return die. Alleen bij
 *   een eerdere FAILED-poging maken we een nieuwe row aan zodat de user
 *   opnieuw kan proberen.
 */
@Service
class PodcastTranslationServiceImpl(
    private val podcastRepo: PodcastRepository,
    private val episodeRepo: PodcastEpisodeLookup,
    private val translator: PodcastTranslator
) : PodcastTranslationService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun lookup(username: String, rssItemId: String): EpisodeLookup? {
        val episode = episodeRepo.findByRssItemId(username, rssItemId) ?: return null
        val existing = podcastRepo.findByTranslatedFromEpisodeGuid(username, episode.guid)
        return EpisodeLookup(
            episodeGuid = episode.guid,
            episodeTitle = episode.title,
            episodeStatus = episode.status.name,
            transcriptCharCount = episode.transcript.length,
            feedUrl = episode.feedUrl,
            feedName = episode.podcastName.ifBlank { episode.feedUrl },
            rssItemId = episode.rssItemId,
            translatedPodcastId = existing?.id,
            translatedPodcastStatus = existing?.status?.name,
            translatedPodcastTitle = existing?.title,
            translatedPodcastErrorMessage = existing?.errorMessage
        )
    }

    override fun startTranslation(username: String, episodeGuid: String): TranslationStart {
        val episode = episodeRepo.get(username, episodeGuid)
            ?: throw TranslationException("Aflevering niet gevonden: $episodeGuid")
        if (episode.status != PodcastEpisodeStatus.DONE) {
            // AC: alleen actief wanneer Engels transcript klaar is.
            throw TranslationException(
                "Aflevering is nog niet klaar (status=${episode.status}). Wacht tot het transcript verwerkt is."
            )
        }
        if (episode.transcript.isBlank()) {
            throw TranslationException("Aflevering heeft (nog) geen transcript")
        }

        val existing = podcastRepo.findByTranslatedFromEpisodeGuid(username, episodeGuid)
        if (existing != null && existing.status != PodcastStatus.FAILED) {
            // Idempotent: DONE → return; in-progress → return + UI gaat pollen.
            log.info(
                "[PodcastTranslate] dubbele start id={} status={} — return bestaande podcast voor user='{}'",
                existing.id, existing.status, username
            )
            return TranslationStart(existing.id, existing.status.name, created = false)
        }

        val podcastNumber = (podcastRepo.load(username).maxOfOrNull { it.podcastNumber } ?: 0) + 1
        val podcast = Podcast(
            id = UUID.randomUUID().toString(),
            title = "${episode.title} (NL)".take(500),
            periodDescription = "vertaling van ${episode.podcastName.ifBlank { "RSS podcast" }}",
            // periodDays/durationMinutes zijn voor de zelf-gegenereerde
            // DevTalk-podcasts; voor een vertaling vullen we 'placeholder'-
            // waarden zodat de DB-defaults niet stuk lopen. De UI gebruikt
            // ze niet als dit een vertaling is (isTranslation=true).
            periodDays = 0,
            durationMinutes = 60,
            status = PodcastStatus.PENDING,
            podcastNumber = podcastNumber,
            ttsProvider = TtsProvider.OPENAI,
            translatedFromEpisodeGuid = episode.guid,
            translatedFromFeedUrl = episode.feedUrl,
            translatedFromFeedName = episode.podcastName.ifBlank { episode.feedUrl },
            translatedFromEpisodeTitle = episode.title,
            translatedFromRssItemId = episode.rssItemId,
            createdAt = java.time.Instant.now()
        )
        val saved = podcastRepo.upsert(username, podcast)
        log.info("[PodcastTranslate] nieuwe vertaal-job id={} episode={} user='{}'",
            saved.id, episodeGuid, username)
        // Cross-bean call zodat de @Async-proxy op een worker-thread dispatcht.
        translator.translate(username, saved.id, episodeGuid)
        return TranslationStart(saved.id, saved.status.name, created = true)
    }
}

/**
 * Wordt door [PodcastTranslationServiceImpl] gegooid wanneer de aanvraag
 * niet door pre-validatie heen komt (geen aflevering, nog geen
 * transcript, etc.). [com.vdzon.newsfeedbackend.podcast.api.PodcastTranslationController]
 * vangt 'm en returnt HTTP 409 met de message.
 */
class TranslationException(message: String) : RuntimeException(message)
