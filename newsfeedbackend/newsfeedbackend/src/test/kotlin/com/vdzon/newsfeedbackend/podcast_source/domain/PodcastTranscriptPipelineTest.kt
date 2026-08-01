package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.PodcastTranscriptRequested
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SF-1739: unit-tests voor de event-driven transcript-fase.
 *
 * Gedekt: retry-backoff (retry_count/next_attempt_at volgens
 * 5m/15m/45m/24h), idempotentie bij dubbele events, respecteren van de
 * backoff-wachtkamer en de garantie dat er nooit twee afleveringen
 * tegelijk verwerkt worden.
 */
class PodcastTranscriptPipelineTest {

    private lateinit var repo: PodcastEpisodeRepository
    private lateinit var processor: PodcastTranscriptProcessor
    private lateinit var pipeline: PodcastTranscriptPipeline

    /** Alles wat de pipeline via [PodcastEpisodeRepository.upsert] opslaat. */
    private lateinit var saved: MutableList<PodcastEpisode>

    private val user = "robbert"
    private val guid = "ep-1"

    /** Mockito.any() op een Kotlin non-null referentie-parameter (zie SF-1467). */
    private fun <T> anyObject(): T {
        Mockito.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun episode(
        guid: String = this.guid,
        status: PodcastEpisodeStatus = PodcastEpisodeStatus.NEEDS_TRANSCRIPT,
        retryCount: Int = 0,
        nextAttemptAt: Instant? = null
    ) = PodcastEpisode(
        username = user,
        guid = guid,
        feedUrl = "https://feed.example/rss",
        status = status,
        retryCount = retryCount,
        nextAttemptAt = nextAttemptAt
    )

    @BeforeEach
    fun setUp() {
        repo = Mockito.mock(PodcastEpisodeRepository::class.java)
        processor = Mockito.mock(PodcastTranscriptProcessor::class.java)
        pipeline = PodcastTranscriptPipeline(repo, processor)
        saved = mutableListOf()
        Mockito.doAnswer { inv ->
            val ep = inv.getArgument<PodcastEpisode>(0)
            saved.add(ep)
            ep
        }.`when`(repo).upsert(anyObject())
    }

    @Test
    fun `event op een NEEDS_TRANSCRIPT-aflevering start de transcript-verwerking`() {
        Mockito.`when`(repo.get(user, guid)).thenReturn(episode())
        Mockito.`when`(processor.processTranscript(user, guid))
            .thenReturn(PodcastTranscriptProcessor.TranscriptResult.Success)

        pipeline.onTranscriptRequested(PodcastTranscriptRequested(user, guid))

        verify(processor).processTranscript(user, guid)
        // Bij Success schrijft de pipeline zelf niets — dat doet de processor.
        assertTrue(saved.isEmpty(), "geen extra upsert bij Success")
    }

    @Test
    fun `rate-limit schrijft retry_count++ en next_attempt_at volgens de backoff-tabel`() {
        // 1e failure → 5m, 2e → 15m, 3e → 45m, 4e+ → 24h.
        val table = listOf(
            0 to Duration.ofMinutes(5),
            1 to Duration.ofMinutes(15),
            2 to Duration.ofMinutes(45),
            3 to Duration.ofHours(24),
            9 to Duration.ofHours(24)
        )
        for ((retryCount, expectedDelay) in table) {
            setUp()
            Mockito.`when`(repo.get(user, guid)).thenReturn(episode(retryCount = retryCount))
            Mockito.`when`(processor.processTranscript(user, guid))
                .thenReturn(PodcastTranscriptProcessor.TranscriptResult.RateLimited(429))
            Mockito.`when`(processor.nextRetryDelay(retryCount)).thenReturn(expectedDelay)

            val before = Instant.now()
            pipeline.onTranscriptRequested(PodcastTranscriptRequested(user, guid))
            val after = Instant.now()

            assertEquals(1, saved.size, "precies één backoff-update verwacht")
            val row = saved.single()
            assertEquals(retryCount + 1, row.retryCount, "retry_count moet ophogen")
            val next = row.nextAttemptAt!!
            assertTrue(
                !next.isBefore(before.plus(expectedDelay)) && !next.isAfter(after.plus(expectedDelay)),
                "next_attempt_at moet ~now+$expectedDelay zijn, was $next"
            )
            // De wachttijd komt uit de bestaande tabel op basis van het aantal
            // eerdere failures — geen eigen (afwijkende) berekening.
            verify(processor).nextRetryDelay(retryCount)
        }
    }

    @Test
    fun `de backoff-tabel van de processor is 5m 15m 45m 24h`() {
        val real = PodcastTranscriptProcessor(
            Mockito.mock(PodcastEpisodeRepository::class.java),
            Mockito.mock(com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastAudioDownloader::class.java),
            Mockito.mock(com.vdzon.newsfeedbackend.media.AudioTranscoder::class.java),
            Mockito.mock(com.vdzon.newsfeedbackend.ai.WhisperClient::class.java),
            Mockito.mock(PodcastEpisodeSummarizer::class.java),
            Mockito.mock(PodcastCardWriter::class.java)
        )
        assertEquals(Duration.ofMinutes(5), real.nextRetryDelay(0))
        assertEquals(Duration.ofMinutes(15), real.nextRetryDelay(1))
        assertEquals(Duration.ofMinutes(45), real.nextRetryDelay(2))
        assertEquals(Duration.ofHours(24), real.nextRetryDelay(3))
        assertEquals(Duration.ofHours(24), real.nextRetryDelay(7))
    }

    @Test
    fun `een aflevering in de backoff-wachtkamer wordt niet opgepakt`() {
        Mockito.`when`(repo.get(user, guid))
            .thenReturn(episode(retryCount = 1, nextAttemptAt = Instant.now().plusSeconds(600)))

        pipeline.onTranscriptRequested(PodcastTranscriptRequested(user, guid))

        verify(processor, never()).processTranscript(anyString(), anyString())
    }

    @Test
    fun `dubbele events leiden niet tot dubbele verwerking`() {
        // Het eerste event verwerkt 'm; daarna staat de aflevering op DONE
        // en slaat het tweede event over (idempotentie).
        Mockito.`when`(repo.get(user, guid))
            .thenReturn(episode(), episode(status = PodcastEpisodeStatus.DONE))
        Mockito.`when`(processor.processTranscript(user, guid))
            .thenReturn(PodcastTranscriptProcessor.TranscriptResult.Success)

        pipeline.onTranscriptRequested(PodcastTranscriptRequested(user, guid))
        pipeline.onTranscriptRequested(PodcastTranscriptRequested(user, guid))

        verify(processor, times(1)).processTranscript(user, guid)
    }

    @Test
    fun `een verdwenen aflevering wordt stil overgeslagen`() {
        Mockito.`when`(repo.get(user, guid)).thenReturn(null)

        pipeline.onTranscriptRequested(PodcastTranscriptRequested(user, guid))

        verify(processor, never()).processTranscript(anyString(), anyString())
    }

    @Test
    fun `een exception uit de processor laat de pipeline niet omvallen`() {
        Mockito.`when`(repo.get(user, guid)).thenReturn(episode())
        Mockito.`when`(processor.processTranscript(user, guid)).thenThrow(RuntimeException("boem"))

        // Geen exception naar de caller (anders klapt de @Async-thread).
        pipeline.onTranscriptRequested(PodcastTranscriptRequested(user, guid))

        verify(processor).processTranscript(user, guid)
    }

    @Test
    fun `er wordt nooit meer dan een aflevering tegelijk verwerkt`() {
        val guids = listOf("ep-a", "ep-b", "ep-c", "ep-d")
        guids.forEach { g -> Mockito.`when`(repo.get(user, g)).thenReturn(episode(guid = g)) }

        val inFlight = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        Mockito.doAnswer {
            val current = inFlight.incrementAndGet()
            maxSeen.updateAndGet { prev -> maxOf(prev, current) }
            Thread.sleep(60)
            inFlight.decrementAndGet()
            PodcastTranscriptProcessor.TranscriptResult.Success
        }.`when`(processor).processTranscript(anyString(), anyString())

        val start = CountDownLatch(1)
        val done = CountDownLatch(guids.size)
        guids.forEach { g ->
            Thread {
                start.await()
                pipeline.handle(user, g)
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "alle threads moeten afronden")

        assertEquals(1, maxSeen.get(), "max één aflevering tegelijk in verwerking")
        verify(processor, times(guids.size)).processTranscript(anyString(), anyString())
    }
}
