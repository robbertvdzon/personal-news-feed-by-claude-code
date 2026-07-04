package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * De ad-hoc-request-flow door de echte app heen: request aanmaken →
 * [com.vdzon.newsfeedbackend.request.domain.AdhocOrchestrator] draait
 * async → Tavily-search + -extract (via HTTP naar de fake-server) →
 * AI-samenvatting (fake OpenAI, action `adhoc_summarize`) → feed-items
 * + status DONE. Plus annuleren, rerun en 404-gedrag.
 */
class RequestsE2eTest : E2eTestBase() {

    companion object {
        /**
         * De TavilyClient doet zonder api-key helemaal geen HTTP-call
         * (hij returnt direct een lege lijst); zet daarom een dummy key
         * zodat de calls echt bij de [FakeContentServer] uitkomen.
         */
        @JvmStatic
        @DynamicPropertySource
        fun tavilyKey(registry: DynamicPropertyRegistry) {
            registry.add("app.tavily.api-key") { "e2e-test-key" }
        }
    }

    /** Complete DTO-body (alle velden expliciet, zoals de frontend doet). */
    private fun createBody(subject: String, maxCount: Int = 2) = """
        {"subject": "$subject", "sourceItemId": null, "sourceItemTitle": null,
         "preferredCount": 1, "maxCount": $maxCount, "extraInstructions": "", "maxAgeDays": 3}
    """.trimIndent()

    /** Serveert een Tavily search- + extract-antwoord met [count] artikelen. */
    private fun serveTavily(count: Int = 2) {
        val results = (1..count).map { n ->
            FakeContentServer.TavilyTestSearchResult(
                title = "Artikel $n",
                url = "https://nieuws.example/artikel-$n",
                content = "Snippet van artikel $n",
                publishedDate = "2026-07-0${n}T08:00:00"
            )
        }
        content.serve("/tavily/search", "application/json", content.tavilySearchJson(results))
        content.serve(
            "/tavily/extract", "application/json",
            content.tavilyExtractJson(results.associate { it.url to "Volledige tekst van ${it.title} over Kotlin." })
        )
    }

    private fun statusOf(user: TestUser, id: String): String =
        getJson("/api/requests", user.token).first { it.path("id").asText() == id }.path("status").asText()

    @Test
    fun `na registratie staan de vaste hourly en daily requests in de lijst en zijn ze niet verwijderbaar`() {
        val user = registerUser("req")

        // ensureFixedRequests draait via de UserRegisteredEvent-listener.
        await {
            getJson("/api/requests", user.token).size() == 2
        }
        val requests = getJson("/api/requests", user.token)
        val hourly = requests.first { it.path("isHourlyUpdate").asBoolean() }
        val daily = requests.first { it.path("isDailySummary").asBoolean() }
        assertEquals("hourly-update-${user.username}", hourly.path("id").asText())
        assertEquals("daily-summary-${user.username}", daily.path("id").asText())
        assertEquals("DONE", hourly.path("status").asText())
        assertEquals("DONE", daily.path("status").asText())

        // Vaste requests mogen niet verwijderd worden: service returnt false → 404.
        assertEquals(404, delete("/api/requests/hourly-update-${user.username}", user.token).status)
        assertEquals(404, delete("/api/requests/daily-summary-${user.username}", user.token).status)
        assertEquals(2, getJson("/api/requests", user.token).size())
    }

    @Test
    fun `adhoc request doorloopt tavily en AI en levert feed-items op`() {
        val user = registerUser("req")
        serveTavily(count = 2)
        openAi.onAction(ExternalCall.ACTION_ADHOC_SUMMARIZE) { "Fake adhoc samenvatting voor de e2e-test." }

        val created = post("/api/requests", user.token, createBody("Kotlin nieuws", maxCount = 2))
        assertEquals(201, created.status)
        val id = created.json(mapper).path("id").asText()
        assertEquals("PENDING", created.json(mapper).path("status").asText())

        await { statusOf(user, id) == "DONE" }
        val done = getJson("/api/requests", user.token).first { it.path("id").asText() == id }
        assertEquals(2, done.path("newItemCount").asInt())

        // Per zoekresultaat één feed-item met de AI-samenvatting.
        val feed = getJson("/api/feed", user.token)
        assertEquals(2, feed.size())
        assertTrue(feed.all { it.path("summary").asText() == "Fake adhoc samenvatting voor de e2e-test." })
        assertTrue(feed.all { it.path("feedReason").asText() == "Geselecteerd voor verzoek 'Kotlin nieuws'" })
        assertTrue(feed.all { it.path("source").asText() == "nieuws.example" })
        val titels = feed.values().map { it.path("title").asText() }.toSet()
        assertEquals(setOf("Artikel 1", "Artikel 2"), titels)
        // published_date uit Tavily wordt afgekapt tot YYYY-MM-DD.
        assertTrue(feed.any { it.path("publishedDate").asText() == "2026-07-01" })

        // De volledige (extract-)tekst zat in de AI-prompt, niet alleen de snippet.
        val calls = openAi.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username)
        assertEquals(2, calls.size)
        assertTrue(calls.any { it.user.contains("Volledige tekst van Artikel 1") })
    }

    @Test
    fun `adhoc request zonder zoekresultaten wordt DONE met nul items en is daarna verwijderbaar`() {
        val user = registerUser("req")
        // Bewust géén /tavily/search geserveerd: de fake-server geeft 404
        // en de TavilyClient vertaalt dat naar een lege resultatenlijst.

        val created = post("/api/requests", user.token, createBody("Onvindbaar onderwerp"))
        val id = created.json(mapper).path("id").asText()

        await { statusOf(user, id) == "DONE" }
        val done = getJson("/api/requests", user.token).first { it.path("id").asText() == id }
        assertEquals(0, done.path("newItemCount").asInt())
        assertEquals(0, getJson("/api/feed", user.token).size())
        assertEquals(0, openAi.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username).size)

        // Een niet-vaste request mag wél verwijderd worden.
        assertEquals(204, delete("/api/requests/$id", user.token).status)
        assertTrue(getJson("/api/requests", user.token).none { it.path("id").asText() == id })
    }

    @Test
    fun `lopende request annuleren zet de status op CANCELLED en stopt de verwerking`() {
        val user = registerUser("req")
        serveTavily(count = 2)

        // Blokkeer de eerste AI-samenvatting zodat de request gegarandeerd
        // nog "onderweg" is op het moment van annuleren.
        val latch = CountDownLatch(1)
        openAi.onAction(ExternalCall.ACTION_ADHOC_SUMMARIZE) {
            latch.await(20, TimeUnit.SECONDS)
            "Vertraagde samenvatting."
        }

        val id = post("/api/requests", user.token, createBody("Traag onderwerp", maxCount = 2))
            .json(mapper).path("id").asText()

        // Wacht tot de orchestrator in de eerste AI-call hangt.
        await { openAi.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username).isNotEmpty() }

        assertEquals(204, post("/api/requests/$id/cancel", user.token).status)
        await { statusOf(user, id) == "CANCELLED" }

        // Laat de AI-call los: de orchestrator ziet vóór artikel 2 de
        // annulering en mag de status niet meer naar DONE flippen.
        latch.countDown()
        Thread.sleep(1500)
        assertEquals("CANCELLED", statusOf(user, id))
        assertEquals(1, openAi.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username).size)
        assertTrue(getJson("/api/feed", user.token).size() <= 1)
    }

    @Test
    fun `rerun van een afgeronde request draait de pipeline opnieuw`() {
        val user = registerUser("req")
        serveTavily(count = 1)
        openAi.onAction(ExternalCall.ACTION_ADHOC_SUMMARIZE) { "Samenvatting run." }

        val id = post("/api/requests", user.token, createBody("Herhaalbaar onderwerp", maxCount = 1))
            .json(mapper).path("id").asText()
        await { statusOf(user, id) == "DONE" }
        assertEquals(1, getJson("/api/feed", user.token).size())

        val rerun = post("/api/requests/$id/rerun", user.token)
        assertEquals(200, rerun.status)
        // De rerun-response is de gereset-te request (teller terug naar 0).
        assertEquals("PENDING", rerun.json(mapper).path("status").asText())
        assertEquals(0, rerun.json(mapper).path("newItemCount").asInt())

        await { statusOf(user, id) == "DONE" }
        val done = getJson("/api/requests", user.token).first { it.path("id").asText() == id }
        assertEquals(1, done.path("newItemCount").asInt())
        // De pipeline liep echt opnieuw: nogmaals een AI-call en een tweede feed-item.
        assertEquals(2, openAi.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username).size)
        assertEquals(2, getJson("/api/feed", user.token).size())
    }

    @Test
    fun `onbekend request-id geeft 404 op delete en rerun`() {
        val user = registerUser("req")

        assertEquals(404, delete("/api/requests/bestaat-niet", user.token).status)
        assertEquals(404, post("/api/requests/bestaat-niet/rerun", user.token).status)
        // NB: cancel op een onbekend id geeft géén 404 — de controller
        // negeert de boolean van service.cancel() en antwoordt altijd 204.
        // Vastgelegd als huidig gedrag (inconsistent met delete/rerun).
        assertEquals(204, post("/api/requests/bestaat-niet/cancel", user.token).status)
    }
}
