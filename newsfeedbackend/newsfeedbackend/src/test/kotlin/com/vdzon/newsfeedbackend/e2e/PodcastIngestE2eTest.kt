package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.domain.PodcastRecoveryScheduler
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * Podcast-ingestion (fase 1: show-notes, transcribeEnabled=false zodat
 * de Whisper-fase buiten scope blijft): feed opslaan → automatische
 * ingestion → card in de RSS-tab → promotie naar de feed.
 *
 * SF-1739: plus twee tests op de event-driven fase 2 — die start nu op
 * een applicatie-event i.p.v. op de verwijderde 2-minuten-tick (de
 * recovery-cron staat in de e2e-suite uit, zie [E2eTestBase]).
 */
class PodcastIngestE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var episodeRepo: PodcastEpisodeRepository

    @Autowired
    private lateinit var recovery: PodcastRecoveryScheduler

    private fun servePodcastFeed(episodes: Int = 2, path: String = "/podcast.xml"): String {
        val eps = (1..episodes).map { n ->
            FakeContentServer.PodcastTestEpisode(
                title = "Aflevering $n",
                guid = "ep-$n",
                audioUrl = content.url("/audio/ep-$n.mp3"),
                showNotes = "In aflevering $n bespreken we teststrategie en Kotlin."
            )
        }
        content.serve(path, "application/rss+xml", content.podcastFeedXml("DevCast", eps))
        return content.url(path)
    }

    @Test
    fun `podcast-feed opslaan ingest afleveringen en promoveert ze naar de feed`() {
        val user = registerUser("podcast")
        val feedUrl = servePodcastFeed(episodes = 2)

        val save = put(
            "/api/podcast-feeds", user.token,
            """{"feeds": [{"url": "$feedUrl", "transcribeEnabled": false}]}"""
        )
        assertEquals(200, save.status)

        // Show-notes-cards verschijnen als PODCAST-items in de RSS-tab.
        await { getJson("/api/rss", user.token).size() == 2 }
        val rssItems = getJson("/api/rss", user.token)
        assertTrue(rssItems.all { it.path("mediaType").asString() == "PODCAST" })
        assertTrue(rssItems.all { it.path("summary").asString() == "Fake podcast-samenvatting." })

        // transcribeEnabled=false → direct feed-promotie (geen 24h wachten).
        await { getJson("/api/feed", user.token).size() == 2 }
        assertTrue(getJson("/api/feed", user.token).all { it.path("mediaType").asString() == "PODCAST" })

        // De show-notes zaten in de AI-prompt (geen Whisper nodig).
        val prompts = openAi.callsFor(ExternalCall.ACTION_PODCAST_EPISODE_SUMMARIZE, user.username)
        assertEquals(2, prompts.size)
        assertTrue(prompts.any { it.user.contains("teststrategie en Kotlin") })
    }

    @Test
    fun `herhaalde refresh ingest geen dubbele afleveringen`() {
        val user = registerUser("podcast")
        val feedUrl = servePodcastFeed(episodes = 2)
        put(
            "/api/podcast-feeds", user.token,
            """{"feeds": [{"url": "$feedUrl", "transcribeEnabled": false}]}"""
        )
        await { getJson("/api/rss", user.token).size() == 2 }
        val aiCallsNaEersteRun = openAi.callsFor(ExternalCall.ACTION_PODCAST_EPISODE_SUMMARIZE, user.username).size

        // RSS-refresh triggert ook de podcast-ingestion (zelfde knop in de UI).
        post("/api/rss/refresh", user.token)
        await {
            getJson("/api/requests", user.token)
                .any { it.path("isHourlyUpdate").asBoolean() && it.path("status").asString() == "DONE" }
        }

        assertEquals(2, getJson("/api/rss", user.token).size())
        assertEquals(
            aiCallsNaEersteRun,
            openAi.callsFor(ExternalCall.ACTION_PODCAST_EPISODE_SUMMARIZE, user.username).size,
            "geen nieuwe afleveringen → geen extra AI-samenvattingen"
        )
    }

    @Test
    fun `top-7-window - van een feed met 10 afleveringen worden er maximaal 7 verwerkt`() {
        val user = registerUser("podcast")
        val feedUrl = servePodcastFeed(episodes = 10)
        put(
            "/api/podcast-feeds", user.token,
            """{"feeds": [{"url": "$feedUrl", "transcribeEnabled": false}]}"""
        )

        await { getJson("/api/rss", user.token).size() == 7 }
        // Even wachten of er niet tóch meer bijkomen.
        Thread.sleep(1500)
        assertEquals(7, getJson("/api/rss", user.token).size())
    }

    @Test
    fun `transcribeEnabled true start de transcript-fase direct via het event`() {
        // Geen scheduler actief in de e2e-suite: als fase 2 toch loopt,
        // kan dat alleen via het PodcastTranscriptRequested-event.
        // De audio-URL wordt bewust niet geserveerd → download 404 →
        // terminale SHOW_NOTES_DONE met een herkenbare foutmelding.
        val user = registerUser("podcast")
        val feedUrl = servePodcastFeed(episodes = 1, path = "/podcast-transcribe.xml")

        val save = put(
            "/api/podcast-feeds", user.token,
            """{"feeds": [{"url": "$feedUrl", "transcribeEnabled": true}]}"""
        )
        assertEquals(200, save.status)

        await { episodeRepo.get(user.username, "ep-1")?.status == PodcastEpisodeStatus.SHOW_NOTES_DONE }
        val ep = episodeRepo.get(user.username, "ep-1")!!
        assertTrue(
            ep.errorMessage?.contains("Audio-download faalde") == true,
            "verwacht dat de transcript-fase daadwerkelijk gedraaid heeft, errorMessage=${ep.errorMessage}"
        )
    }

    @Test
    fun `de recovery-job pakt een door restart gemiste aflevering alsnog op`() {
        val user = registerUser("podcast")
        val feedUrl = servePodcastFeed(episodes = 1, path = "/podcast-recovery.xml")
        put(
            "/api/podcast-feeds", user.token,
            """{"feeds": [{"url": "$feedUrl", "transcribeEnabled": false}]}"""
        )
        await { getJson("/api/rss", user.token).size() == 1 }

        // Simuleer "event verloren gegaan bij een restart": de aflevering
        // staat op NEEDS_TRANSCRIPT met een verlopen next_attempt_at,
        // maar er is nooit een event voor gepubliceerd.
        val seeded = episodeRepo.get(user.username, "ep-1")!!
        episodeRepo.upsert(
            seeded.copy(
                status = PodcastEpisodeStatus.NEEDS_TRANSCRIPT,
                retryCount = 1,
                nextAttemptAt = Instant.now().minusSeconds(60),
                errorMessage = null
            )
        )
        assertNotNull(episodeRepo.get(user.username, "ep-1")?.nextAttemptAt)

        recovery.recover()

        await { episodeRepo.get(user.username, "ep-1")?.status != PodcastEpisodeStatus.NEEDS_TRANSCRIPT }
        assertEquals(
            PodcastEpisodeStatus.SHOW_NOTES_DONE,
            episodeRepo.get(user.username, "ep-1")?.status
        )
    }

    @Test
    fun `onbereikbare podcast-feed geeft 400 met Nederlandse foutmelding`() {
        val user = registerUser("podcast")
        val resp = put(
            "/api/podcast-feeds", user.token,
            """{"feeds": [{"url": "${content.url("/bestaat-niet.xml")}", "transcribeEnabled": false}]}"""
        )
        assertEquals(400, resp.status)
        assertTrue(resp.json(mapper).path("error").asString().contains("Kon feed niet ophalen"))
    }
}
