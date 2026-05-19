package com.vdzon.newsfeedbackend.podcast

import java.time.Instant

interface PodcastService {
    fun list(username: String): List<Podcast>
    fun get(username: String, id: String): Podcast?
    fun create(username: String, dto: CreatePodcastDto): Podcast
    fun delete(username: String, id: String): Boolean
    fun audioBytes(username: String, id: String): ByteArray?
}

/**
 * Status-flow:
 *   PENDING → DETERMINING_TOPICS → GENERATING_SCRIPT → GENERATING_AUDIO
 *     → DONE / FAILED   (zelf-gegenereerde DevTalk-podcasts)
 *
 *   PENDING → TRANSLATING → TTS_GENERATING
 *     → DONE / FAILED   (KAN-63: vertaling van een RSS-podcast-aflevering)
 */
enum class PodcastStatus {
    PENDING,
    DETERMINING_TOPICS,
    GENERATING_SCRIPT,
    GENERATING_AUDIO,
    TRANSLATING,
    TTS_GENERATING,
    DONE,
    FAILED
}
enum class TtsProvider { OPENAI, ELEVENLABS }

data class Podcast(
    val id: String,
    val title: String = "",
    val periodDescription: String = "",
    val periodDays: Int = 7,
    val durationMinutes: Int = 15,
    val status: PodcastStatus = PodcastStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val scriptText: String? = null,
    val topics: List<String> = emptyList(),
    val durationSeconds: Int? = null,
    val customTopics: List<String> = emptyList(),
    val ttsProvider: TtsProvider = TtsProvider.OPENAI,
    val podcastNumber: Int = 0,
    val generationSeconds: Int? = null,
    /**
     * KAN-63: gevuld wanneer deze podcast een Nederlandse vertaling is
     * van een aflevering uit een RSS-podcast-feed. Null voor de
     * zelf-gegenereerde DevTalk-podcasts. De guid wijst naar
     * `podcast_episodes.guid`; samen met `username` is dit ook de
     * idempotency-sleutel.
     */
    val translatedFromEpisodeGuid: String? = null,
    val translatedFromFeedUrl: String? = null,
    /** Display-naam van de bron-podcast voor de "vertaald van X"-badge. */
    val translatedFromFeedName: String? = null,
    val translatedFromEpisodeTitle: String? = null,
    /** rss_items.id van de bron-aflevering — voor de tap-navigatie terug. */
    val translatedFromRssItemId: String? = null,
    /** Bij status=FAILED: korte foutomschrijving die de UI mag tonen. */
    val errorMessage: String? = null
) {
    val isTranslation: Boolean get() = translatedFromEpisodeGuid != null
}

data class CreatePodcastDto(
    val periodDays: Int = 7,
    val durationMinutes: Int = 15,
    val customTopics: List<String> = emptyList(),
    val ttsProvider: TtsProvider = TtsProvider.OPENAI
)
