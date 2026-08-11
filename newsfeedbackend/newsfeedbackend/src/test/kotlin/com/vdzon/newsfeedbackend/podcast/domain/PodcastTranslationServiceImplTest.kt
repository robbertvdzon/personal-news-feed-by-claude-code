package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.common.ConflictException
import com.vdzon.newsfeedbackend.common.NotFoundException
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.TtsProvider
import com.vdzon.newsfeedbackend.podcast.infrastructure.PodcastRepository
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeLookup
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant

/**
 * SF-1467: unit-tests voor de guard-clause- en idempotency-logica van
 * [PodcastTranslationServiceImpl], zelfde patroon als
 * `SettingsServiceImplSavePodcastFeedsTest` (Mockito.mock in
 * @BeforeEach, geen e2e-harnas, geen ffmpeg/Whisper nodig).
 *
 * [PodcastTranslator.translate] genereert de nieuwe podcast-id zelf via
 * `UUID.randomUUID()`, dus die kan niet vooraf gematched worden. Om
 * `any()`/ArgumentCaptor.capture()-NPE's op Kotlin non-null parameters
 * te vermijden (zie repo-agent-tip pnf-mockito-first-use-sf1345) vangen
 * we het echte argument via een `doAnswer`-lambda en verifiëren we
 * daarna met de zo verkregen concrete waarde i.p.v. matchers.
 */
@ExtendWith(MockitoExtension::class)
class PodcastTranslationServiceImplTest {

    private lateinit var podcastRepo: PodcastRepository
    private lateinit var episodeRepo: PodcastEpisodeLookup
    private lateinit var translator: PodcastTranslator
    private lateinit var service: PodcastTranslationServiceImpl

    @BeforeEach
    fun setUp() {
        podcastRepo = mock(PodcastRepository::class.java)
        episodeRepo = mock(PodcastEpisodeLookup::class.java)
        translator = mock(PodcastTranslator::class.java)
        service = PodcastTranslationServiceImpl(podcastRepo, episodeRepo, translator)
    }

    private fun episode(
        guid: String = "episode-guid-1",
        status: PodcastEpisodeStatus = PodcastEpisodeStatus.DONE,
        transcript: String = "This is the transcript.",
        title: String = "Episode Title",
        feedUrl: String = "https://example.com/feed.xml",
        podcastName: String = "Example Podcast",
        rssItemId: String? = "rss-item-1"
    ) = PodcastEpisode(
        username = "alice",
        guid = guid,
        feedUrl = feedUrl,
        podcastName = podcastName,
        title = title,
        status = status,
        transcript = transcript,
        rssItemId = rssItemId
    )

    private fun podcast(
        id: String = "podcast-1",
        status: PodcastStatus = PodcastStatus.DONE,
        translatedFromEpisodeGuid: String? = "episode-guid-1",
        title: String = "Existing translation",
        errorMessage: String? = null,
        podcastNumber: Int = 1
    ) = Podcast(
        id = id,
        title = title,
        status = status,
        podcastNumber = podcastNumber,
        translatedFromEpisodeGuid = translatedFromEpisodeGuid,
        errorMessage = errorMessage,
        createdAt = Instant.now()
    )

    /**
     * Mockito's `any()` retourneert `null`, wat op een Kotlin non-null
     * parameter een "any() must not be null"-NPE kan geven (zie
     * repo-agent-tip pnf-mockito-first-use-sf1345). Deze generieke
     * wrapper omzeilt dat: de cast naar `T` gebeurt in erased vorm,
     * dus zonder Kotlin's parameter-null-check.
     */
    private fun <T> anyObject(): T {
        org.mockito.ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    /** Vangt het [Podcast]-argument van een [PodcastRepository.upsert]-call en echoot het terug, zoals de echte repo. */
    private fun captureUpserted(): MutableList<Podcast> {
        val captured = mutableListOf<Podcast>()
        doAnswer { invocation ->
            val saved = invocation.getArgument<Podcast>(1)
            captured += saved
            saved
        }.`when`(podcastRepo).upsert(anyString(), anyObject())
        return captured
    }

    // ===================== startTranslation =====================

    @Test
    fun `startTranslation throws NotFoundException when episode is not found`() {
        whenever(episodeRepo.get("alice", "missing-guid")).thenReturn(null)

        assertThrows(NotFoundException::class.java) {
            service.startTranslation("alice", "missing-guid")
        }

        verify(episodeRepo).get("alice", "missing-guid")
        verifyNoInteractions(podcastRepo)
        verifyNoInteractions(translator)
    }

    @Test
    fun `startTranslation throws ConflictException when episode status is not DONE`() {
        val ep = episode(status = PodcastEpisodeStatus.TRANSCRIBING)
        whenever(episodeRepo.get("alice", ep.guid)).thenReturn(ep)

        assertThrows(ConflictException::class.java) {
            service.startTranslation("alice", ep.guid)
        }

        verifyNoInteractions(podcastRepo)
        verifyNoInteractions(translator)
    }

    @Test
    fun `startTranslation throws ConflictException when episode is DONE but transcript is blank`() {
        val ep = episode(status = PodcastEpisodeStatus.DONE, transcript = "   ")
        whenever(episodeRepo.get("alice", ep.guid)).thenReturn(ep)

        assertThrows(ConflictException::class.java) {
            service.startTranslation("alice", ep.guid)
        }

        verifyNoInteractions(podcastRepo)
        verifyNoInteractions(translator)
    }

    @Test
    fun `startTranslation returns existing translation idempotently when its status is not FAILED`() {
        val ep = episode(status = PodcastEpisodeStatus.DONE, transcript = "Hello world.")
        whenever(episodeRepo.get("alice", ep.guid)).thenReturn(ep)
        val existing = podcast(id = "existing-podcast-id", status = PodcastStatus.DONE, translatedFromEpisodeGuid = ep.guid)
        whenever(podcastRepo.findByTranslatedFromEpisodeGuid("alice", ep.guid)).thenReturn(existing)

        val result = service.startTranslation("alice", ep.guid)

        assertEquals("existing-podcast-id", result.podcastId)
        assertEquals("DONE", result.status)
        assertEquals(false, result.created)
        verify(podcastRepo).findByTranslatedFromEpisodeGuid("alice", ep.guid)
        verifyNoMoreInteractions(podcastRepo)
        verifyNoInteractions(translator)
    }

    @Test
    fun `startTranslation starts a new job when existing translation has FAILED status`() {
        val ep = episode(status = PodcastEpisodeStatus.DONE, transcript = "Hello world.")
        whenever(episodeRepo.get("alice", ep.guid)).thenReturn(ep)
        val existing = podcast(id = "existing-podcast-id", status = PodcastStatus.FAILED, translatedFromEpisodeGuid = ep.guid)
        whenever(podcastRepo.findByTranslatedFromEpisodeGuid("alice", ep.guid)).thenReturn(existing)
        whenever(podcastRepo.load("alice"))
            .thenReturn(mutableListOf(podcast(id = "p1", podcastNumber = 3)))
        val captured = captureUpserted()

        val result = service.startTranslation("alice", ep.guid)

        assertEquals(true, result.created)
        assertEquals("PENDING", result.status)
        assertEquals(1, captured.size)
        val saved = captured[0]
        assertEquals(result.podcastId, saved.id)
        assertEquals(4, saved.podcastNumber)
        assertEquals(PodcastStatus.PENDING, saved.status)
        assertEquals(TtsProvider.OPENAI, saved.ttsProvider)
        assertEquals(ep.guid, saved.translatedFromEpisodeGuid)
        verify(translator).translate("alice", saved.id, ep.guid)
    }

    @Test
    fun `startTranslation happy path fills a new Podcast correctly and starts translation when no existing translation exists`() {
        val ep = episode(
            status = PodcastEpisodeStatus.DONE,
            transcript = "Hello world.",
            title = "My Episode",
            feedUrl = "https://example.com/feed.xml",
            podcastName = "Example Podcast",
            rssItemId = "rss-item-42"
        )
        whenever(episodeRepo.get("alice", ep.guid)).thenReturn(ep)
        whenever(podcastRepo.findByTranslatedFromEpisodeGuid("alice", ep.guid)).thenReturn(null)
        whenever(podcastRepo.load("alice")).thenReturn(mutableListOf())
        val captured = captureUpserted()

        val result = service.startTranslation("alice", ep.guid)

        assertEquals(true, result.created)
        assertEquals("PENDING", result.status)
        assertEquals(1, captured.size)
        val saved = captured[0]
        assertEquals(result.podcastId, saved.id)
        assertEquals("My Episode (NL)", saved.title)
        assertEquals(1, saved.podcastNumber)
        assertEquals(PodcastStatus.PENDING, saved.status)
        assertEquals(TtsProvider.OPENAI, saved.ttsProvider)
        assertEquals(ep.guid, saved.translatedFromEpisodeGuid)
        assertEquals("https://example.com/feed.xml", saved.translatedFromFeedUrl)
        assertEquals("Example Podcast", saved.translatedFromFeedName)
        assertEquals("My Episode", saved.translatedFromEpisodeTitle)
        assertEquals("rss-item-42", saved.translatedFromRssItemId)
        verify(translator).translate("alice", saved.id, ep.guid)
    }

    // ===================== lookup =====================

    @Test
    fun `lookup returns null when episode is not found`() {
        whenever(episodeRepo.findByRssItemId("alice", "missing-rss-item")).thenReturn(null)

        val result = service.lookup("alice", "missing-rss-item")

        assertNull(result)
        verifyNoInteractions(podcastRepo)
    }

    @Test
    fun `lookup returns an EpisodeLookup with translatedPodcastId null when no translation exists yet`() {
        val ep = episode(rssItemId = "rss-item-42")
        whenever(episodeRepo.findByRssItemId("alice", "rss-item-42")).thenReturn(ep)
        whenever(podcastRepo.findByTranslatedFromEpisodeGuid("alice", ep.guid)).thenReturn(null)

        val result = service.lookup("alice", "rss-item-42")

        assertEquals(ep.guid, result?.episodeGuid)
        assertEquals(ep.title, result?.episodeTitle)
        assertEquals(ep.status.name, result?.episodeStatus)
        assertEquals(ep.transcript.length, result?.transcriptCharCount)
        assertEquals(ep.feedUrl, result?.feedUrl)
        assertEquals(ep.podcastName, result?.feedName)
        assertEquals(ep.rssItemId, result?.rssItemId)
        assertNull(result?.translatedPodcastId)
        assertNull(result?.translatedPodcastStatus)
        assertNull(result?.translatedPodcastTitle)
        assertNull(result?.translatedPodcastErrorMessage)
    }

    @Test
    fun `lookup fills all translatedPodcast fields when a translation already exists`() {
        val ep = episode(rssItemId = "rss-item-42")
        whenever(episodeRepo.findByRssItemId("alice", "rss-item-42")).thenReturn(ep)
        val existing = podcast(
            id = "existing-podcast-id",
            status = PodcastStatus.FAILED,
            translatedFromEpisodeGuid = ep.guid,
            title = "My Episode (NL)",
            errorMessage = "TTS-call faalde"
        )
        whenever(podcastRepo.findByTranslatedFromEpisodeGuid("alice", ep.guid)).thenReturn(existing)

        val result = service.lookup("alice", "rss-item-42")

        assertEquals("existing-podcast-id", result?.translatedPodcastId)
        assertEquals("FAILED", result?.translatedPodcastStatus)
        assertEquals("My Episode (NL)", result?.translatedPodcastTitle)
        assertEquals("TTS-call faalde", result?.translatedPodcastErrorMessage)
    }
}
