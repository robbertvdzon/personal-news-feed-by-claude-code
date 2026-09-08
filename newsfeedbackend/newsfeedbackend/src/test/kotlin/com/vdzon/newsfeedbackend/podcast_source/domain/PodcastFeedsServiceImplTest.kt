package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.podcast_source.PodcastIngestionTrigger
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastFeedFetcher
import com.vdzon.newsfeedbackend.settings.PodcastFeed
import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * Unit-tests op de verhuisde validatielogica (SF-1683): alleen nieuwe,
 * niet-blanco URLs worden gefetcht, een mislukte fetch geeft de exacte
 * Nederlandse melding, en het happy path slaat op vóór het triggeren.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PodcastFeedsServiceImplTest {

    private lateinit var settingsService: SettingsService
    private lateinit var trigger: PodcastIngestionTrigger
    private lateinit var fetcher: PodcastFeedFetcher
    private lateinit var service: PodcastFeedsServiceImpl

    @BeforeEach
    fun setUp() {
        settingsService = mock(SettingsService::class.java)
        trigger = mock(PodcastIngestionTrigger::class.java)
        fetcher = mock(PodcastFeedFetcher::class.java)
        service = PodcastFeedsServiceImpl(settingsService, trigger, fetcher)
    }

    private fun ok() = PodcastFeedFetcher.FetchResult(
        ok = true, podcastName = "Podcast", episodes = emptyList()
    )

    private fun failed(errorMessage: String?) = PodcastFeedFetcher.FetchResult(
        ok = false, podcastName = "", episodes = emptyList(), errorMessage = errorMessage
    )

    @Test
    fun `fetches only new urls and skips existing and blank ones`() {
        val existingUrl = "https://example.com/bestaand.xml"
        val newUrl = "https://example.com/nieuw.xml"
        `when`(settingsService.getPodcastFeeds("alice"))
            .thenReturn(PodcastFeedsSettings(feeds = listOf(PodcastFeed(url = existingUrl))))
        `when`(fetcher.fetch(newUrl, "alice")).thenReturn(ok())
        val body = PodcastFeedsSettings(
            feeds = listOf(
                PodcastFeed(url = existingUrl),
                PodcastFeed(url = "   "),
                PodcastFeed(url = newUrl)
            )
        )
        `when`(settingsService.savePodcastFeeds("alice", body)).thenReturn(body)

        val result = service.savePodcastFeeds("alice", body)

        assertEquals(body, result)
        verify(fetcher).fetch(newUrl, "alice")
        verify(fetcher, never()).fetch(existingUrl, "alice")
        verify(fetcher, never()).fetch("   ", "alice")
    }

    @Test
    fun `saves then triggers ingestion in that order`() {
        val url = "https://example.com/nieuw.xml"
        `when`(settingsService.getPodcastFeeds("alice")).thenReturn(PodcastFeedsSettings())
        `when`(fetcher.fetch(url, "alice")).thenReturn(ok())
        val body = PodcastFeedsSettings(feeds = listOf(PodcastFeed(url = url)))
        val saved = PodcastFeedsSettings(feeds = listOf(PodcastFeed(url = url, transcribeEnabled = false)))
        `when`(settingsService.savePodcastFeeds("alice", body)).thenReturn(saved)

        val result = service.savePodcastFeeds("alice", body)

        assertEquals(saved, result)
        val order = inOrder(settingsService, trigger)
        order.verify(settingsService).savePodcastFeeds("alice", body)
        order.verify(trigger).trigger("alice")
    }

    @Test
    fun `failed fetch rejects with dutch message and does not save or trigger`() {
        val url = "https://example.com/kapot.xml"
        `when`(settingsService.getPodcastFeeds("alice")).thenReturn(PodcastFeedsSettings())
        `when`(fetcher.fetch(url, "alice")).thenReturn(failed("404"))
        val body = PodcastFeedsSettings(feeds = listOf(PodcastFeed(url = url)))

        val ex = assertThrows(BadRequestException::class.java) {
            service.savePodcastFeeds("alice", body)
        }

        assertEquals("Kon feed niet ophalen: $url (404)", ex.message)
        verify(settingsService, never()).savePodcastFeeds("alice", body)
        verifyNoInteractions(trigger)
    }

    @Test
    fun `failed fetch without error message falls back to onbekende fout`() {
        val url = "https://example.com/kapot.xml"
        `when`(settingsService.getPodcastFeeds("alice")).thenReturn(PodcastFeedsSettings())
        `when`(fetcher.fetch(url, "alice")).thenReturn(failed(null))
        val body = PodcastFeedsSettings(feeds = listOf(PodcastFeed(url = url)))

        val ex = assertThrows(BadRequestException::class.java) {
            service.savePodcastFeeds("alice", body)
        }

        assertEquals("Kon feed niet ophalen: $url (onbekende fout)", ex.message)
    }
}
