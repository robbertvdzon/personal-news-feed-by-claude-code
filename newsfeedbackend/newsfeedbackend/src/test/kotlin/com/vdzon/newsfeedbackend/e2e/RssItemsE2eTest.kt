package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.RssService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * De RSS-tab-endpoints door de echte app heen: item-acties (read/unread,
 * ster, feedback), markAllRead, cleanup/delete, de reselect-flow en het
 * transcript-endpoint.
 *
 * Items worden — net als in [FeedE2eTest] — rechtstreeks via de publieke
 * module-API [RssService] geseed; alleen de twee reselect-tests draaien
 * eerst een echte refresh, omdat reselect pas representatief is als de
 * items door de echte pipeline heen zijn gekomen.
 *
 * LET OP: geseede id's moeten UUID-vorm hebben —
 * [FakeOpenAiChatClient.extractCandidateIds] vist de kandidaten met een
 * UUID-regex uit de selectie-prompt; met bv. "item-1" zou reselect
 * stilzwijgend niets doen.
 */
class RssItemsE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var rssService: RssService

    @Autowired
    private lateinit var episodeRepo: PodcastEpisodeRepository

    private fun seed(username: String, item: RssItem): RssItem = rssService.upsert(username, item)

    private fun newItem(
        title: String,
        timestamp: Instant = Instant.now(),
        isRead: Boolean = false,
        starred: Boolean = false,
        mediaType: String = "ARTICLE"
    ) = RssItem(
        id = UUID.randomUUID().toString(),
        title = title,
        summary = "samenvatting van $title",
        url = "https://example.test/${UUID.randomUUID()}",
        timestamp = timestamp,
        isRead = isRead,
        starred = starred,
        mediaType = mediaType
    )

    private fun rssItems(user: TestUser) = getJson("/api/rss", user.token)

    private fun rssItem(user: TestUser, id: String) =
        rssItems(user).values().first { it.path("id").asText() == id }

    // ---- item-acties ---------------------------------------------------

    @Test
    fun `read en unread markeren`() {
        val user = registerUser("rssitems")
        val item = seed(user.username, newItem("Te lezen"))

        assertEquals(200, put("/api/rss/${item.id}/read", user.token).status)
        assertTrue(rssItem(user, item.id).path("isRead").asBoolean())

        assertEquals(200, put("/api/rss/${item.id}/unread", user.token).status)
        assertFalse(rssItem(user, item.id).path("isRead").asBoolean())
    }

    @Test
    fun `ster togglen en feedback zetten`() {
        val user = registerUser("rssitems")
        val item = seed(user.username, newItem("Sterretje"))

        assertEquals(200, put("/api/rss/${item.id}/star", user.token).status)
        assertTrue(rssItem(user, item.id).path("starred").asBoolean())

        assertEquals(200, put("/api/rss/${item.id}/star", user.token).status)
        assertFalse(rssItem(user, item.id).path("starred").asBoolean())

        assertEquals(
            200,
            put("/api/rss/${item.id}/feedback", user.token, """{"liked": true}""").status
        )
        assertEquals(true, rssItem(user, item.id).path("liked").asBoolean())
    }

    // ---- markAllRead ---------------------------------------------------

    @Test
    fun `markAllRead telt alleen de vooraf ongelezen items en is idempotent`() {
        val user = registerUser("rssitems")
        seed(user.username, newItem("A"))
        seed(user.username, newItem("B"))
        seed(user.username, newItem("C", isRead = true))

        val resp = post("/api/rss/markAllRead", user.token)
        assertEquals(200, resp.status)
        assertEquals(2, resp.json(mapper).path("updated").asInt())
        assertTrue(rssItems(user).all { it.path("isRead").asBoolean() })

        val again = post("/api/rss/markAllRead", user.token)
        assertEquals(200, again.status)
        assertEquals(0, again.json(mapper).path("updated").asInt())
    }

    // ---- cleanup en delete ---------------------------------------------

    @Test
    fun `cleanup verwijdert oude items maar respecteert keep-vlaggen`() {
        val user = registerUser("rssitems")
        val vijftigDagen = Instant.now().minus(50, ChronoUnit.DAYS)
        seed(user.username, newItem("oud-gelezen", timestamp = vijftigDagen, isRead = true))
        seed(user.username, newItem("oud-met-ster", timestamp = vijftigDagen, isRead = true, starred = true))
        seed(user.username, newItem("oud-ongelezen", timestamp = vijftigDagen, isRead = false))
        seed(user.username, newItem("vers", isRead = true))

        val resp = delete(
            "/api/rss/cleanup?olderThanDays=30&keepStarred=true&keepLiked=true&keepUnread=true",
            user.token
        )
        assertEquals(200, resp.status)
        assertEquals(1, resp.json(mapper).path("removed").asInt())

        val titels = rssItems(user).values().map { it.path("title").asText() }.toSet()
        assertEquals(setOf("oud-met-ster", "oud-ongelezen", "vers"), titels)
    }

    @Test
    fun `item verwijderen laat de andere items staan`() {
        val user = registerUser("rssitems")
        val weg = seed(user.username, newItem("Weg ermee"))
        val blijft = seed(user.username, newItem("Blijft staan"))

        assertEquals(200, delete("/api/rss/${weg.id}", user.token).status)

        val overgebleven = rssItems(user)
        assertEquals(1, overgebleven.size())
        assertEquals(blijft.id, overgebleven[0].path("id").asText())
    }

    // ---- reselect ------------------------------------------------------

    /** Zelfde fake-feed als [RssRefreshE2eTest.serveDefaultFeed]: twee artikelen. */
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

    private fun scoreAll(inFeed: Boolean, reason: String) {
        openAi.onAction(ExternalCall.ACTION_FEED_SCORE) { call ->
            FakeOpenAiChatClient.extractCandidateIds(call.user)
                .joinToString(prefix = "[", postfix = "]") {
                    """{"id": "$it", "inFeed": $inFeed, "reason": "$reason"}"""
                }
        }
    }

    /** Wacht tot de (async) refresh helemaal klaar is — anders slaat reselect op de lock af. */
    private fun awaitRefreshDone(user: TestUser) {
        await {
            getJson("/api/requests", user.token)
                .any { it.path("isHourlyUpdate").asBoolean() && it.path("status").asText() == "DONE" }
        }
    }

    @Test
    fun `reselect promoveert eerder afgewezen items alsnog naar de feed`() {
        val user = registerUser("rssitems")
        val feedUrl = serveDefaultFeed()
        assertEquals(200, put("/api/rss-feeds", user.token, """{"feeds": ["$feedUrl"]}""").status)

        // Ronde 1: AI wijst alles af → items in de RSS-tab, feed leeg.
        scoreAll(inFeed = false, reason = "Eerste ronde afgewezen")
        assertEquals(200, post("/api/rss/refresh", user.token).status)
        await { rssItems(user).size() == 2 }
        awaitRefreshDone(user)
        assertTrue(rssItems(user).none { it.path("inFeed").asBoolean() })
        assertTrue(rssItems(user).all { it.path("feedReason").asText().contains("Eerste ronde afgewezen") })
        assertEquals(0, getJson("/api/feed", user.token).size())
        assertTrue(openAi.callsFor(ExternalCall.ACTION_FEED_SUMMARIZE, user.username).isEmpty())

        // Ronde 2: AI accepteert alles met een herkenbaar andere reden.
        scoreAll(inFeed = true, reason = "Tweede ronde geselecteerd")
        val resp = post("/api/rss/reselect", user.token)
        assertEquals(200, resp.status)
        assertEquals("ok", resp.json(mapper).path("status").asText())

        await {
            rssItems(user).let { items ->
                items.size() == 2 && items.all {
                    it.path("inFeed").asBoolean() &&
                        it.path("feedReason").asText().contains("Tweede ronde geselecteerd")
                }
            }
        }
        await { getJson("/api/feed", user.token).size() == 2 }
        assertTrue(
            openAi.callsFor(ExternalCall.ACTION_FEED_SUMMARIZE, user.username).isNotEmpty(),
            "reselect hoort feed-items te genereren (stap 4 van de pipeline)"
        )
    }

    @Test
    fun `reselect zonder verdicts laat de bestaande selectie ongemoeid`() {
        val user = registerUser("rssitems")
        val feedUrl = serveDefaultFeed()
        assertEquals(200, put("/api/rss-feeds", user.token, """{"feeds": ["$feedUrl"]}""").status)

        // Ronde 1: default-fake selecteert alles → 2 feed-items.
        assertEquals(200, post("/api/rss/refresh", user.token).status)
        await { getJson("/api/feed", user.token).size() == 2 }
        awaitRefreshDone(user)

        val voor = rssItems(user).values()
            .associate { it.path("id").asText() to (it.path("inFeed").asBoolean() to it.path("feedReason").asText()) }
        val scoreCallsVoor = openAi.callsFor(ExternalCall.ACTION_FEED_SCORE, user.username).size
        val summarizeCallsVoor = openAi.callsFor(ExternalCall.ACTION_FEED_SUMMARIZE, user.username).size

        // Ronde 2: AI geeft een lege lijst terug.
        openAi.onAction(ExternalCall.ACTION_FEED_SCORE) { "[]" }
        assertEquals(200, post("/api/rss/reselect", user.token).status)

        // Reselect laat (anders dan refresh) geen request-status achter; het
        // extra FEED_SCORE-call-record is hier het afrondingssignaal.
        await { openAi.callsFor(ExternalCall.ACTION_FEED_SCORE, user.username).size == scoreCallsVoor + 1 }

        val na = rssItems(user).values()
            .associate { it.path("id").asText() to (it.path("inFeed").asBoolean() to it.path("feedReason").asText()) }
        assertEquals(voor, na, "zonder verdicts hoort inFeed/feedReason exact gelijk te blijven")
        assertEquals(
            summarizeCallsVoor,
            openAi.callsFor(ExternalCall.ACTION_FEED_SUMMARIZE, user.username).size,
            "geen verdicts → geen nieuwe feed-items"
        )
    }

    // ---- transcript ----------------------------------------------------

    @Test
    fun `transcript geeft 404 voor een gewoon artikel`() {
        val user = registerUser("rssitems")
        val item = seed(user.username, newItem("Gewoon artikel"))

        assertEquals(404, get("/api/rss/${item.id}/transcript", user.token).status)
    }

    @Test
    fun `transcript geeft de tekst van de gekoppelde podcast-aflevering`() {
        val user = registerUser("rssitems")
        val item = seed(user.username, newItem("Podcast-aflevering", mediaType = "PODCAST"))
        episodeRepo.upsert(
            PodcastEpisode(
                username = user.username,
                guid = "ep-${UUID.randomUUID()}",
                feedUrl = "https://example.test/podcast.xml",
                rssItemId = item.id,
                transcript = "Dit is het volledige transcript van de aflevering."
            )
        )

        val resp = get("/api/rss/${item.id}/transcript", user.token)
        assertEquals(200, resp.status)
        assertEquals(
            "Dit is het volledige transcript van de aflevering.",
            resp.json(mapper).path("transcript").asText()
        )
    }
}
