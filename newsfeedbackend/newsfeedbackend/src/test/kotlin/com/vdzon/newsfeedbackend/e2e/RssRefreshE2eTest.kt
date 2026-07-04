package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De volledige RSS-refresh-flow door de echte app heen:
 * feed registreren → refresh triggeren → fetchen van de fake-server →
 * AI-samenvatting (fake) → AI-selectie (fake) → feed-item-generatie.
 */
class RssRefreshE2eTest : E2eTestBase() {

    private fun serveDefaultFeed(): String {
        content.serveArticle("/artikel/1", "Kotlin 3.0 aangekondigd", "Kotlin 3.0 brengt nieuwe features.")
        content.serveArticle("/artikel/2", "Spring Boot 4 release", "Spring Boot 4 is uitgebracht.")
        val feedXml = content.rssFeedXml(
            "Tech Nieuws", listOf(
                FakeContentServer.RssTestItem("Kotlin 3.0 aangekondigd", content.url("/artikel/1")),
                FakeContentServer.RssTestItem("Spring Boot 4 release", content.url("/artikel/2"))
            )
        )
        content.serve("/feed.xml", "application/rss+xml", feedXml)
        return content.url("/feed.xml")
    }

    @Test
    fun `refresh haalt artikelen op, laat AI selecteren en genereert feed-items`() {
        val user = registerUser("rss")
        val feedUrl = serveDefaultFeed()

        assertEquals(
            200,
            put("/api/rss-feeds", user.token, """{"feeds": ["$feedUrl"]}""").status
        )
        assertEquals(200, post("/api/rss/refresh", user.token).status)

        // RSS-items verschijnen met de (fake) AI-samenvatting en categorie.
        await { getJson("/api/rss", user.token).size() == 2 }
        val rssItems = getJson("/api/rss", user.token)
        assertTrue(rssItems.all { it.path("summary").asText().contains("Fake samenvatting") })
        assertTrue(rssItems.all { it.path("category").asText() == "overig" })

        // Beide items worden (door de fake selectie) gepromoveerd naar de feed.
        await { getJson("/api/feed", user.token).size() == 2 }
        val feedItems = getJson("/api/feed", user.token)
        assertTrue(feedItems.all { it.path("titleNl").asText() == "Fake NL titel" })
        assertTrue(feedItems.all { it.path("shortSummary").asText() == "Fake korte samenvatting." })

        // De pipeline heeft de verwachte AI-stappen doorlopen.
        assertEquals(2, openAi.callsFor(ExternalCall.ACTION_RSS_SUMMARIZE, user.username).size)
        assertEquals(1, openAi.callsFor(ExternalCall.ACTION_FEED_SCORE, user.username).size)
        assertEquals(2, openAi.callsFor(ExternalCall.ACTION_FEED_SUMMARIZE, user.username).size)

        // De volledige artikeltekst (via ArticleFetcher) zat in de samenvattingsprompt.
        val summarizePrompts = openAi.callsFor(ExternalCall.ACTION_FEED_SUMMARIZE, user.username)
        assertTrue(summarizePrompts.any { it.user.contains("Kotlin 3.0 brengt nieuwe features") })
    }

    @Test
    fun `tweede refresh is idempotent - geen dubbele items`() {
        val user = registerUser("rss")
        val feedUrl = serveDefaultFeed()
        put("/api/rss-feeds", user.token, """{"feeds": ["$feedUrl"]}""")

        post("/api/rss/refresh", user.token)
        await { getJson("/api/feed", user.token).size() == 2 }

        post("/api/rss/refresh", user.token)
        // Refresh loopt async: wacht tot de hourly-update-request weer DONE is.
        await {
            getJson("/api/requests", user.token)
                .any { it.path("isHourlyUpdate").asBoolean() && it.path("status").asText() == "DONE" }
        }
        assertEquals(2, getJson("/api/rss", user.token).size())
        assertEquals(2, getJson("/api/feed", user.token).size())
    }

    @Test
    fun `artikelen die AI afwijst komen niet in de feed maar wel in rss met reden`() {
        val user = registerUser("rss")
        val feedUrl = serveDefaultFeed()
        put("/api/rss-feeds", user.token, """{"feeds": ["$feedUrl"]}""")

        // Script: wijs alles af.
        openAi.onAction(ExternalCall.ACTION_FEED_SCORE) { call ->
            val ids = FakeOpenAiChatClient.extractCandidateIds(call.user)
            ids.joinToString(prefix = "[", postfix = "]") {
                """{"id": "$it", "inFeed": false, "reason": "Niet interessant voor deze test"}"""
            }
        }

        post("/api/rss/refresh", user.token)
        await { getJson("/api/rss", user.token).size() == 2 }
        await {
            getJson("/api/rss", user.token).all {
                it.path("feedReason").asText().contains("Niet interessant")
            }
        }
        assertEquals(0, getJson("/api/feed", user.token).size())
    }

    @Test
    fun `kapotte feed-url laat de refresh niet crashen`() {
        val user = registerUser("rss")
        // 404-URL + één werkende feed: de werkende feed moet gewoon verwerkt worden.
        val feedUrl = serveDefaultFeed()
        put(
            "/api/rss-feeds", user.token,
            """{"feeds": ["${content.url("/bestaat-niet.xml")}", "$feedUrl"]}"""
        )

        post("/api/rss/refresh", user.token)
        await { getJson("/api/rss", user.token).size() == 2 }
        await {
            getJson("/api/requests", user.token)
                .any { it.path("isHourlyUpdate").asBoolean() && it.path("status").asText() == "DONE" }
        }
    }
}
