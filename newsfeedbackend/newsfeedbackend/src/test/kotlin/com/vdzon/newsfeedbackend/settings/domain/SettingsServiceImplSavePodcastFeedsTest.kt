package com.vdzon.newsfeedbackend.settings.domain

import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.settings.PodcastFeed
import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings
import com.vdzon.newsfeedbackend.settings.infrastructure.CategorySettingsRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.EventDenylistRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.EventPreferencesRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.PodcastFeedsRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.RssFeedsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SettingsServiceImplSavePodcastFeedsTest {

    private lateinit var podcastFeedsRepo: PodcastFeedsRepository
    private lateinit var service: SettingsServiceImpl

    @BeforeEach
    fun setUp() {
        podcastFeedsRepo = mock(PodcastFeedsRepository::class.java)
        service = SettingsServiceImpl(
            categoryRepo = mock(CategorySettingsRepository::class.java),
            rssFeedsRepo = mock(RssFeedsRepository::class.java),
            podcastFeedsRepo = podcastFeedsRepo,
            eventPreferencesRepo = mock(EventPreferencesRepository::class.java),
            eventDenylistRepo = mock(EventDenylistRepository::class.java)
        )
    }

    @Test
    fun `saves settings when all feed urls are valid public http-s urls`() {
        val settings = PodcastFeedsSettings(feeds = listOf(PodcastFeed(url = "https://example.com/feed.xml")))

        val result = service.savePodcastFeeds("alice", settings)

        assertEquals(settings, result)
        verify(podcastFeedsRepo).save("alice", settings)
    }

    @Test
    fun `rejects non-http-s scheme and does not persist`() {
        val settings = PodcastFeedsSettings(feeds = listOf(PodcastFeed(url = "file:///etc/passwd")))

        assertThrows(BadRequestException::class.java) {
            service.savePodcastFeeds("alice", settings)
        }
        verifyNoSave()
    }

    @Test
    fun `rejects feed url resolving to a private address and does not persist`() {
        val settings = PodcastFeedsSettings(feeds = listOf(PodcastFeed(url = "http://127.0.0.1:8080/feed.xml")))

        val ex = assertThrows(BadRequestException::class.java) {
            service.savePodcastFeeds("alice", settings)
        }
        assertEquals(true, ex.message?.contains("127.0.0.1") ?: false)
        verifyNoSave()
    }

    private fun verifyNoSave() {
        verifyNoInteractions(podcastFeedsRepo)
    }
}
