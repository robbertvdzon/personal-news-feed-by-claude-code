package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.request.domain.RequestServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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

    /** Concreet nodig voor de assertie dat er geen cancel-vlag achterblijft. */
    @Autowired
    private lateinit var requestService: RequestServiceImpl

    /** Complete DTO-body (alle velden expliciet, zoals de frontend doet). */
    private fun createBody(subject: String, maxCount: Int = 2) = """
        {"subject": "$subject", "sourceItemId": null, "sourceItemTitle": null,
         "preferredCount": 1, "maxCount": $maxCount, "extraInstructions": "", "maxAgeDays": 3}
    """.trimIndent()

    /** Serveert een Tavily search- + extract-antwoord met [count] artikelen. */
    /** Gescript agent-antwoord: [count] gevonden en samengevatte artikelen. */
    private fun adhocResult(count: Int, summary: String = "Fake adhoc samenvatting voor de e2e-test."): String =
        (1..count).joinToString(prefix = """{"items": [""", postfix = "]}") { n ->
            """{"title": "Artikel $n", "url": "https://nieuws.example/artikel-$n", "source": "nieuws.example", "publishedDate": "2026-07-0${n}", "summary": "$summary"}"""
        }

    private fun statusOf(user: TestUser, id: String): String =
        getJson("/api/requests", user.token).first { it.path("id").asString() == id }.path("status").asString()

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
        assertEquals("hourly-update-${user.username}", hourly.path("id").asString())
        assertEquals("daily-summary-${user.username}", daily.path("id").asString())
        assertEquals("DONE", hourly.path("status").asString())
        assertEquals("DONE", daily.path("status").asString())

        // Vaste requests mogen niet verwijderd worden: service returnt false → 404.
        assertEquals(404, delete("/api/requests/hourly-update-${user.username}", user.token).status)
        assertEquals(404, delete("/api/requests/daily-summary-${user.username}", user.token).status)
        assertEquals(2, getJson("/api/requests", user.token).size())
    }

    @Test
    fun `adhoc request laat de agent zoeken en samenvatten en levert feed-items op`() {
        val user = registerUser("req")
        ai.onAction(ExternalCall.ACTION_ADHOC_SUMMARIZE) { adhocResult(count = 2) }

        val created = post("/api/requests", user.token, createBody("Kotlin nieuws", maxCount = 2))
        assertEquals(201, created.status)
        val id = created.json(mapper).path("id").asString()
        assertEquals("PENDING", created.json(mapper).path("status").asString())

        await { statusOf(user, id) == "DONE" }
        val done = getJson("/api/requests", user.token).first { it.path("id").asString() == id }
        assertEquals(2, done.path("newItemCount").asInt())

        // Per gevonden artikel één feed-item met de AI-samenvatting.
        val feed = getJson("/api/feed", user.token)
        assertEquals(2, feed.size())
        assertTrue(feed.all { it.path("summary").asString() == "Fake adhoc samenvatting voor de e2e-test." })
        assertTrue(feed.all { it.path("feedReason").asString() == "Geselecteerd voor verzoek 'Kotlin nieuws'" })
        assertTrue(feed.all { it.path("source").asString() == "nieuws.example" })
        val titels = feed.values().map { it.path("title").asString() }.toSet()
        assertEquals(setOf("Artikel 1", "Artikel 2"), titels)
        assertTrue(feed.any { it.path("publishedDate").asString() == "2026-07-01" })

        // Eén agent-job met onderwerp en limieten in de opdracht.
        val calls = ai.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username)
        assertEquals(1, calls.size)
        assertTrue(calls.single().prompt.contains("Kotlin nieuws"))
        assertTrue(calls.single().prompt.contains("maximaal 2"))
    }

    @Test
    fun `adhoc request zonder zoekresultaten wordt DONE met nul items en is daarna verwijderbaar`() {
        val user = registerUser("req")
        // Default-fake: de agent vindt niets.

        val created = post("/api/requests", user.token, createBody("Onvindbaar onderwerp"))
        val id = created.json(mapper).path("id").asString()

        await { statusOf(user, id) == "DONE" }
        val done = getJson("/api/requests", user.token).first { it.path("id").asString() == id }
        assertEquals(0, done.path("newItemCount").asInt())
        assertEquals(0, getJson("/api/feed", user.token).size())
        assertEquals(1, ai.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username).size)

        // Een niet-vaste request mag wél verwijderd worden.
        assertEquals(204, delete("/api/requests/$id", user.token).status)
        assertTrue(getJson("/api/requests", user.token).none { it.path("id").asString() == id })
    }

    @Test
    fun `lopende request annuleren zet de status op CANCELLED en stopt de verwerking`() {
        val user = registerUser("req")

        // Blokkeer de agent-job zodat de request gegarandeerd nog
        // "onderweg" is op het moment van annuleren.
        val latch = CountDownLatch(1)
        ai.onAction(ExternalCall.ACTION_ADHOC_SUMMARIZE) {
            latch.await(20, TimeUnit.SECONDS)
            adhocResult(count = 2, summary = "Vertraagde samenvatting.")
        }

        val id = post("/api/requests", user.token, createBody("Traag onderwerp", maxCount = 2))
            .json(mapper).path("id").asString()

        // Wacht tot de orchestrator in de agent-job hangt.
        await { ai.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username).isNotEmpty() }

        assertEquals(204, post("/api/requests/$id/cancel", user.token).status)
        await { statusOf(user, id) == "CANCELLED" }

        // Laat de job los: de orchestrator ziet de annulering en mag de
        // status niet meer naar DONE flippen of items opslaan.
        latch.countDown()
        Thread.sleep(1500)
        assertEquals("CANCELLED", statusOf(user, id))
        assertEquals(1, ai.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username).size)
        assertEquals(0, getJson("/api/feed", user.token).size())
    }

    @Test
    fun `rerun van een afgeronde request draait de pipeline opnieuw`() {
        val user = registerUser("req")
        ai.onAction(ExternalCall.ACTION_ADHOC_SUMMARIZE) { adhocResult(count = 1, summary = "Samenvatting run.") }

        val id = post("/api/requests", user.token, createBody("Herhaalbaar onderwerp", maxCount = 1))
            .json(mapper).path("id").asString()
        await { statusOf(user, id) == "DONE" }
        assertEquals(1, getJson("/api/feed", user.token).size())

        val rerun = post("/api/requests/$id/rerun", user.token)
        assertEquals(200, rerun.status)
        // De rerun-response is de gereset-te request (teller terug naar 0).
        assertEquals("PENDING", rerun.json(mapper).path("status").asString())
        assertEquals(0, rerun.json(mapper).path("newItemCount").asInt())

        await { statusOf(user, id) == "DONE" }
        val done = getJson("/api/requests", user.token).first { it.path("id").asString() == id }
        assertEquals(1, done.path("newItemCount").asInt())
        // De pipeline liep echt opnieuw: nogmaals een AI-call en een tweede feed-item.
        val runs = ai.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, user.username)
        assertEquals(2, runs.size)
        assertTrue(runs[0].variant != runs[1].variant, "een rerun hoort een nieuwe runtime-job te krijgen")
        assertEquals(2, getJson("/api/feed", user.token).size())
    }

    @Test
    fun `onbekend request-id geeft 404 op delete en rerun`() {
        val user = registerUser("req")

        assertEquals(404, delete("/api/requests/bestaat-niet", user.token).status)
        assertEquals(404, post("/api/requests/bestaat-niet/rerun", user.token).status)
        // Cancel volgt sinds SF-2051 hetzelfde patroon als delete/rerun:
        // een onbekend (of andermans) id geeft 404 en laat geen cancel-vlag
        // achter.
        assertEquals(404, post("/api/requests/bestaat-niet/cancel", user.token).status)
        assertTrue(requestService.cancellation.keys.none { it.endsWith("/bestaat-niet") })
    }

    @Test
    fun `een andere gebruiker kan een lopend verzoek niet annuleren`() {
        val owner = registerUser("req")
        val attacker = registerUser("req")

        // Blokkeer de agent-job zodat het verzoek van de eigenaar echt nog
        // onderweg is tijdens de annuleerpoging.
        val latch = CountDownLatch(1)
        ai.onAction(ExternalCall.ACTION_ADHOC_SUMMARIZE) {
            latch.await(20, TimeUnit.SECONDS)
            adhocResult(count = 2, summary = "Vertraagde samenvatting.")
        }

        val id = post("/api/requests", owner.token, createBody("Traag onderwerp", maxCount = 2))
            .json(mapper).path("id").asString()
        await { ai.callsFor(ExternalCall.ACTION_ADHOC_SUMMARIZE, owner.username).isNotEmpty() }

        // De aanvaller kent het id (het lekt via /ws/requests) maar mag er niets mee.
        assertEquals(404, post("/api/requests/$id/cancel", attacker.token).status)
        // Geen sleutel die de verwerking van de eigenaar kan raken.
        assertTrue(requestService.cancellation.keys.none { it.endsWith("/$id") })
        assertEquals("PROCESSING", statusOf(owner, id))

        // De verwerking van de eigenaar loopt gewoon door naar DONE.
        latch.countDown()
        await { statusOf(owner, id) == "DONE" }
        assertEquals(2, getJson("/api/feed", owner.token).size())
    }
}
