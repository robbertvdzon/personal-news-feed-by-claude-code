package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Podcast-ingestion (fase 1: show-notes, transcribeEnabled=false zodat
 * de Whisper-fase buiten scope blijft): feed opslaan → automatische
 * ingestion → card in de RSS-tab → promotie naar de feed.
 */
class PodcastIngestE2eTest : E2eTestBase() {

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
        assertTrue(rssItems.all { it.path("mediaType").asText() == "PODCAST" })
        assertTrue(rssItems.all { it.path("summary").asText() == "Fake podcast-samenvatting." })

        // transcribeEnabled=false → direct feed-promotie (geen 24h wachten).
        await { getJson("/api/feed", user.token).size() == 2 }
        assertTrue(getJson("/api/feed", user.token).all { it.path("mediaType").asText() == "PODCAST" })

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
                .any { it.path("isHourlyUpdate").asBoolean() && it.path("status").asText() == "DONE" }
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
    fun `onbereikbare podcast-feed geeft 400 met Nederlandse foutmelding`() {
        val user = registerUser("podcast")
        val resp = put(
            "/api/podcast-feeds", user.token,
            """{"feeds": [{"url": "${content.url("/bestaat-niet.xml")}", "transcribeEnabled": false}]}"""
        )
        assertEquals(400, resp.status)
        assertTrue(resp.json(mapper).path("error").asText().contains("Kon feed niet ophalen"))
    }
}
