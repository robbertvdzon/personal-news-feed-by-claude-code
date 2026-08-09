package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallQuery
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.RssService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * De twee vaste verzoeken die elke gebruiker bij registratie krijgt
 * (`hourly-update-<user>` en `daily-summary-<user>`) en wat er gebeurt als
 * je ze handmatig opnieuw start via `POST /api/requests/{id}/rerun`:
 *
 * - `daily-summary-*` → [com.vdzon.newsfeedbackend.rss.domain.RssScheduler.generateDailySummary]
 * - `hourly-update-*` → [com.vdzon.newsfeedbackend.rss.RssService.triggerRefresh]
 *
 * De routering zit in
 * [com.vdzon.newsfeedbackend.rss.domain.FixedRequestRerunListener]; de
 * [com.vdzon.newsfeedbackend.request.domain.AdhocOrchestrator] luistert naar
 * hetzelfde `RequestRerunEvent` en moet zich voor deze twee id's juist
 * stilhouden. Beide kanten worden hier vastgelegd.
 *
 * Feed- en rss-items worden — net als in [FeedE2eTest] en [RssItemsE2eTest] —
 * rechtstreeks via de publieke module-API's geseed, zodat de tijdvensters van
 * de samenvatting exact stuurbaar zijn; getriggerd wordt uitsluitend via HTTP.
 */
class FixedRequestsE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var feedService: FeedService

    @Autowired
    private lateinit var rssService: RssService

    @Autowired
    private lateinit var externalCalls: ExternalCallQuery

    private val today: LocalDate get() = LocalDate.now()
    private val summaryFeedId: String get() = "daily-summary-feed-$today"

    // ---- helpers -------------------------------------------------------

    private fun hourlyId(user: TestUser) = "hourly-update-${user.username}"
    private fun dailyId(user: TestUser) = "daily-summary-${user.username}"

    /**
     * Registreert een gebruiker en wacht tot `UserRegisteredListener` de twee
     * vaste verzoeken heeft aangemaakt — zonder die wacht geeft de rerun 404.
     */
    private fun registerUserWithFixedRequests(prefix: String = "fixed"): TestUser {
        val user = registerUser(prefix)
        await { getJson("/api/requests", user.token).size() == 2 }
        return user
    }

    private fun requestById(user: TestUser, id: String): JsonNode =
        getJson("/api/requests", user.token).values().first { it.path("id").asString() == id }

    private fun feedItemById(user: TestUser, id: String): List<JsonNode> =
        getJson("/api/feed", user.token).values().filter { it.path("id").asString() == id }

    private fun seedFeedItem(user: TestUser, title: String, createdAt: Instant): FeedItem =
        feedService.save(
            user.username,
            FeedItem(
                id = UUID.randomUUID().toString(),
                title = title,
                summary = "samenvatting van $title",
                createdAt = createdAt
            )
        )

    private fun seedRssItem(user: TestUser, title: String, timestamp: Instant): RssItem =
        rssService.upsert(
            user.username,
            RssItem(
                id = UUID.randomUUID().toString(),
                title = title,
                summary = "samenvatting van $title",
                snippet = "snippet van $title",
                url = "https://example.test/${UUID.randomUUID()}",
                timestamp = timestamp
            )
        )

    /** Registreert een werkende RSS-feed bij de gebruiker (zonder te refreshen). */
    private fun registerRssFeed(user: TestUser): String {
        content.serveArticle("/artikel/1", "Kotlin 3.0 aangekondigd", "Kotlin 3.0 brengt nieuwe features.")
        content.serveArticle("/artikel/2", "Spring Boot 4 release", "Spring Boot 4 is uitgebracht.")
        val feedXml = content.rssFeedXml(
            "Tech Nieuws", listOf(
                FakeContentServer.RssTestItem("Kotlin 3.0 aangekondigd", content.url("/artikel/1")),
                FakeContentServer.RssTestItem("Spring Boot 4 release", content.url("/artikel/2"))
            )
        )
        content.serve("/feed.xml", "application/rss+xml", feedXml)
        val feedUrl = content.url("/feed.xml")
        assertEquals(200, put("/api/rss-feeds", user.token, """{"feeds": ["$feedUrl"]}""").status)
        return feedUrl
    }

    /**
     * Criterium 5: de ad-hoc-tak (Tavily-search + adhoc_summarize) mag voor een
     * vast verzoek helemaal niet gelopen hebben. `TavilyClient.search` logt óók
     * zonder api-key een `tavily_search`-rij (status `error`), dus een lege
     * lijst bewijst echt dat `AdhocOrchestrator.process` vroegtijdig teruggaf.
     * Alleen aanroepen ná een positief await-anker, anders is het een race.
     */
    private fun assertGeenAdhocVerwerking(user: TestUser) {
        assertTrue(
            externalCalls.query(username = user.username, action = ExternalCall.ACTION_TAVILY_SEARCH).isEmpty(),
            "AdhocOrchestrator heeft het rerun-event van een vast verzoek toch opgepakt"
        )
        assertEquals(0, openAi.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username).size)
    }

    // ---- daily summary -------------------------------------------------

    @Test
    fun `rerun van het dagelijkse verzoek maakt een samenvattings-feed-item en zet het verzoek op DONE`() {
        val user = registerUserWithFixedRequests()
        val markdown = "# Nieuwsbriefing\n\n- Eerste punt\n- Tweede punt\n"
        openAi.onAction(ExternalCall.ACTION_DAILY_SUMMARY) { markdown }

        seedFeedItem(user, "Vers feed-artikel", Instant.now())
        seedRssItem(user, "Vers rss-artikel", Instant.now())
        // Feed geregistreerd maar niet ververst: als het rerun-event per ongeluk
        // óók de RSS-pipeline zou starten, worden de nul-asserties hieronder rood.
        registerRssFeed(user)

        assertEquals(200, post("/api/requests/${dailyId(user)}/rerun", user.token).status)

        // Anker: rerun zet newItemCount eerst op 0, de samenvatting weer op 1.
        await {
            val req = requestById(user, dailyId(user))
            req.path("status").asString() == "DONE" && req.path("newItemCount").asInt() == 1
        }

        val summaries = feedItemById(user, summaryFeedId)
        assertEquals(1, summaries.size)
        val summary = summaries.single()
        assertTrue(summary.path("isSummary").asBoolean())
        assertEquals("Dagelijkse samenvatting $today", summary.path("title").asString())
        assertEquals(markdown, summary.path("summary").asString())
        assertEquals(today.toString(), summary.path("publishedDate").asString())

        // Criterium 4 (tegenhanger): de RSS-refresh-tak is niet gestart.
        assertEquals(0, openAi.callsFor(ExternalCall.ACTION_RSS_SUMMARIZE, user.username).size)
        assertEquals(0, openAi.callsFor(ExternalCall.ACTION_FEED_SCORE, user.username).size)
        assertEquals(1, getJson("/api/rss", user.token).size(), "alleen het geseede rss-item hoort er te staan")
        assertGeenAdhocVerwerking(user)
    }

    @Test
    fun `de dagelijkse samenvatting krijgt alleen feed-items van 24 uur en rss-items van 7 dagen mee`() {
        val user = registerUserWithFixedRequests()
        openAi.onAction(ExternalCall.ACTION_DAILY_SUMMARY) { "Samenvatting op basis van de aangeboden context." }

        // Onwaarschijnlijke, unieke titels zodat prompt-matching niet broos is.
        val versFeed = "Zeldzaam feed-artikel ${UUID.randomUUID()}"
        val oudFeed = "Zeldzaam oud feed-artikel ${UUID.randomUUID()}"
        val versRss = "Zeldzaam rss-artikel ${UUID.randomUUID()}"
        val oudRss = "Zeldzaam oud rss-artikel ${UUID.randomUUID()}"

        seedFeedItem(user, versFeed, Instant.now().minus(2, ChronoUnit.HOURS))
        seedFeedItem(user, oudFeed, Instant.now().minus(3, ChronoUnit.DAYS))
        seedRssItem(user, versRss, Instant.now().minus(2, ChronoUnit.DAYS))
        seedRssItem(user, oudRss, Instant.now().minus(30, ChronoUnit.DAYS))
        assertEquals(2, getJson("/api/rss", user.token).size())

        assertEquals(200, post("/api/requests/${dailyId(user)}/rerun", user.token).status)
        await {
            val req = requestById(user, dailyId(user))
            req.path("status").asString() == "DONE" && req.path("newItemCount").asInt() == 1
        }

        val calls = openAi.callsFor(ExternalCall.ACTION_DAILY_SUMMARY, user.username)
        assertEquals(1, calls.size)
        val prompt = calls.single().user
        assertTrue(prompt.contains(versFeed), "recent feed-item ontbreekt in de prompt")
        assertTrue(prompt.contains(versRss), "recent rss-item ontbreekt in de prompt")
        assertFalse(prompt.contains(oudFeed), "feed-item ouder dan 24 uur zit toch in de prompt")
        assertFalse(prompt.contains(oudRss), "rss-item ouder dan 7 dagen zit toch in de prompt")
    }

    @Test
    fun `twee reruns op dezelfde dag leveren een samenvatting op, met de inhoud van de laatste run`() {
        val user = registerUserWithFixedRequests()
        val run = AtomicInteger(0)
        openAi.onAction(ExternalCall.ACTION_DAILY_SUMMARY) { "Samenvatting van run ${run.incrementAndGet()}." }
        seedFeedItem(user, "Vers feed-artikel", Instant.now())

        assertEquals(200, post("/api/requests/${dailyId(user)}/rerun", user.token).status)
        await { feedItemById(user, summaryFeedId).any { it.path("summary").asString() == "Samenvatting van run 1." } }

        // Pas rerunnen als de eerste run aantoonbaar klaar is; anders kunnen de
        // twee generaties elkaar overlappen en is de eindtoestand niet bepaald.
        assertEquals(200, post("/api/requests/${dailyId(user)}/rerun", user.token).status)
        await { feedItemById(user, summaryFeedId).any { it.path("summary").asString() == "Samenvatting van run 2." } }

        assertEquals(1, feedItemById(user, summaryFeedId).size)
        assertEquals(2, openAi.callsFor(ExternalCall.ACTION_DAILY_SUMMARY, user.username).size)
        assertEquals(1, requestById(user, dailyId(user)).path("newItemCount").asInt())
    }

    // ---- hourly update -------------------------------------------------

    @Test
    fun `rerun van het uurlijkse verzoek start de RSS-refresh en niet de dagelijkse samenvatting`() {
        val user = registerUserWithFixedRequests()
        registerRssFeed(user)

        assertEquals(200, post("/api/requests/${hourlyId(user)}/rerun", user.token).status)

        // Anker: de refresh-pipeline is echt doorlopen (items + AI-stappen) en
        // het vaste verzoek staat weer op DONE.
        await { getJson("/api/rss", user.token).size() == 2 }
        await { requestById(user, hourlyId(user)).path("status").asString() == "DONE" }
        assertEquals(2, openAi.callsFor(ExternalCall.ACTION_RSS_SUMMARIZE, user.username).size)
        assertEquals(1, openAi.callsFor(ExternalCall.ACTION_FEED_SCORE, user.username).size)

        // Criterium 4: de samenvattings-tak is niet meegelift.
        assertEquals(0, openAi.callsFor(ExternalCall.ACTION_DAILY_SUMMARY, user.username).size)
        assertTrue(feedItemById(user, summaryFeedId).isEmpty())
        assertGeenAdhocVerwerking(user)
    }
}
