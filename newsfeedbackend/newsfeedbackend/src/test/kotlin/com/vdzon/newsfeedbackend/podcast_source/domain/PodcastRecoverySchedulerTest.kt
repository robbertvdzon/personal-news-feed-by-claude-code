package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.PodcastTranscriptRequested
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.rss.PodcastPromotionRequested
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration

/**
 * SF-1739: unit-tests voor het uurlijkse vangnet.
 *
 * Gedekt: een aflevering die door een restart of een verlopen backoff
 * bleef hangen wordt alsnog opgepakt, en de show-notes-timeout-promotie
 * zet de `feed_promotion_attempted_at`-marker vóór het event (geen
 * herhaalde AI-calls).
 */
class PodcastRecoverySchedulerTest {

    private lateinit var repo: PodcastEpisodeRepository
    private lateinit var events: ApplicationEventPublisher
    private lateinit var scheduler: PodcastRecoveryScheduler

    /** Alles wat de job publiceert, in volgorde. */
    private lateinit var published: MutableList<Any>

    /** Mockito.any() op een Kotlin non-null referentie-parameter (zie SF-1467). */
    private fun <T> anyObject(): T {
        Mockito.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun episode(
        guid: String,
        rssItemId: String? = null,
        status: PodcastEpisodeStatus = PodcastEpisodeStatus.NEEDS_TRANSCRIPT
    ) = PodcastEpisode(
        username = "robbert",
        guid = guid,
        feedUrl = "https://feed.example/rss",
        status = status,
        rssItemId = rssItemId
    )

    @BeforeEach
    fun setUp() {
        repo = Mockito.mock(PodcastEpisodeRepository::class.java)
        events = Mockito.mock(ApplicationEventPublisher::class.java)
        published = mutableListOf()
        Mockito.doAnswer { inv -> published.add(inv.getArgument(0)); null }
            .`when`(events).publishEvent(anyObject<Any>())
        Mockito.`when`(repo.findReadyForTranscript(anyObject(), anyInt())).thenReturn(emptyList())
        Mockito.`when`(repo.findShowNotesExpiredForPromotion(anyObject(), anyObject()))
            .thenReturn(emptyList())
        scheduler = PodcastRecoveryScheduler(repo, events, 24)
    }

    @Test
    fun `afleveringen met verlopen next_attempt_at worden alsnog hertriggerd`() {
        // Scenario: het oorspronkelijke event ging verloren bij een restart.
        Mockito.`when`(repo.findReadyForTranscript(anyObject(), anyInt()))
            .thenReturn(listOf(episode("ep-1"), episode("ep-2")))

        scheduler.recover()

        val transcriptEvents = published.filterIsInstance<PodcastTranscriptRequested>()
        assertEquals(2, transcriptEvents.size)
        assertEquals(listOf("ep-1", "ep-2"), transcriptEvents.map { it.guid })
        assertTrue(transcriptEvents.all { it.username == "robbert" })
    }

    @Test
    fun `zonder achterstand publiceert de job niets`() {
        scheduler.recover()

        assertTrue(published.isEmpty(), "geen events verwacht: $published")
    }

    @Test
    fun `de job hertriggert hoogstens MAX_EPISODES_PER_RUN afleveringen`() {
        scheduler.recover()

        verify(repo).findReadyForTranscript(anyObject(), Mockito.eq(PodcastRecoveryScheduler.MAX_EPISODES_PER_RUN))
    }

    @Test
    fun `show-notes-timeout zet de marker voor het promotie-event`() {
        Mockito.`when`(repo.findShowNotesExpiredForPromotion(anyObject(), anyObject()))
            .thenReturn(listOf(episode("ep-oud", rssItemId = "rss-1")))
        val marked = mutableListOf<Pair<String, String>>()
        Mockito.doAnswer { inv ->
            marked.add(inv.getArgument<String>(0) to inv.getArgument<String>(1)); 1
        }.`when`(repo).markFeedPromotionAttempted(anyString(), anyString(), anyObject())

        scheduler.recover()

        // Marker eerst, event daarna — anders zou een AI-afwijzing elke
        // run opnieuw een Claude-selectie-call opleveren.
        val order = Mockito.inOrder(repo, events)
        order.verify(repo).markFeedPromotionAttempted(anyString(), anyString(), anyObject())
        order.verify(events).publishEvent(anyObject<Any>())
        assertEquals(listOf("robbert" to "ep-oud"), marked)

        val promotions = published.filterIsInstance<PodcastPromotionRequested>()
        assertEquals(1, promotions.size)
        assertEquals("rss-1", promotions.single().rssItemId)
    }

    @Test
    fun `een mislukte marker blokkeert het promotie-event (anti-loop)`() {
        Mockito.`when`(repo.findShowNotesExpiredForPromotion(anyObject(), anyObject()))
            .thenReturn(listOf(episode("ep-oud", rssItemId = "rss-1")))
        Mockito.`when`(repo.markFeedPromotionAttempted(anyString(), anyString(), anyObject()))
            .thenThrow(RuntimeException("db weg"))

        scheduler.recover()

        assertTrue(
            published.filterIsInstance<PodcastPromotionRequested>().isEmpty(),
            "zonder marker geen promotie-event — anders herhaalde AI-calls"
        )
    }

    @Test
    fun `een aflevering zonder rss_item_id wordt niet gepromoot`() {
        Mockito.`when`(repo.findShowNotesExpiredForPromotion(anyObject(), anyObject()))
            .thenReturn(listOf(episode("ep-oud", rssItemId = null)))

        scheduler.recover()

        verify(repo, never()).markFeedPromotionAttempted(anyString(), anyString(), anyObject())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `de promotie-timeout komt uit de property`() {
        val seen = mutableListOf<Duration>()
        Mockito.doAnswer { inv -> seen.add(inv.getArgument(1)); emptyList<PodcastEpisode>() }
            .`when`(repo).findShowNotesExpiredForPromotion(anyObject(), anyObject())

        PodcastRecoveryScheduler(repo, events, 6).recover()

        assertEquals(listOf(Duration.ofHours(6)), seen)
    }

    @Test
    fun `een fout in de transcript-stap blokkeert de promotie-stap niet`() {
        Mockito.`when`(repo.findReadyForTranscript(anyObject(), anyInt()))
            .thenThrow(RuntimeException("db weg"))
        Mockito.`when`(repo.findShowNotesExpiredForPromotion(anyObject(), anyObject()))
            .thenReturn(listOf(episode("ep-oud", rssItemId = "rss-1")))

        scheduler.recover()

        assertEquals(1, published.filterIsInstance<PodcastPromotionRequested>().size)
    }
}
