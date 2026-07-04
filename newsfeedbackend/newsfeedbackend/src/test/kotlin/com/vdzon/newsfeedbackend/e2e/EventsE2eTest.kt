package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * De event-discovery-flow (KAN-65/68) door de echte app heen:
 * POST /api/events/discover → [com.vdzon.newsfeedbackend.events.domain.EventDiscoveryPipeline]
 * draait async → Tavily-search (fake-server) + AI-extractie (fake OpenAI,
 * action `event_discovery`) → events in GET /api/events + aankondigings-
 * feed-item. Plus delete→denylist en de datum-verrijkingsstap.
 *
 * NB: de seed-extractie, de "similar"-call én de datum-lookup loggen
 * allemaal onder dezelfde action `event_discovery`; de handler
 * onderscheidt ze op het `subject`-veld.
 */
class EventsE2eTest : E2eTestBase() {

    companion object {
        /**
         * De TavilyClient doet zonder api-key geen HTTP-call; met een
         * dummy key komen de searches echt bij de [FakeContentServer] uit.
         */
        @JvmStatic
        @DynamicPropertySource
        fun tavilyKey(registry: DynamicPropertyRegistry) {
            registry.add("app.tavily.api-key") { "e2e-test-key" }
        }
    }

    // Dynamische (toekomstige) datums zodat de events altijd binnen het
    // discovery-window vallen, hoe laat deze test ook draait.
    private val startDate: LocalDate = LocalDate.now().plusMonths(2)
    private val endDate: LocalDate = startDate.plusDays(2)

    /**
     * Registreert een user met precies één event-voorkeur en zonder
     * actieve categorieën, zodat een discovery-run deterministisch is:
     * 1 seed-search + 1 seed-extract + 1 "similar"-call.
     */
    private fun registerEventUser(preference: String): TestUser {
        val user = registerUser("events")
        // Categorieën leegmaken (alleen de systeemcategorie "overig"
        // blijft over en die doet niet mee aan discovery) zodat er geen
        // extra categorie-searches bijkomen.
        assertEquals(200, put("/api/settings", user.token, "[]").status)
        assertEquals(
            200,
            put("/api/settings/event-preferences", user.token, """{"names": ["$preference"]}""").status
        )
        return user
    }

    /** Standaard Tavily-antwoord; alle searches (seed + datum-lookup) delen dit pad. */
    private fun serveTavilySearch() {
        content.serve(
            "/tavily/search", "application/json",
            content.tavilySearchJson(
                listOf(
                    FakeContentServer.TavilyTestSearchResult(
                        title = "TestConf 2026 aangekondigd",
                        url = "https://testconf.example/2026",
                        content = "TestConf 2026 vindt plaats in Amsterdam."
                    )
                )
            )
        )
    }

    /**
     * Script de fake OpenAI: de seed-extractie geeft [seedEventsJson],
     * de "similar"-call en de datum-lookup zijn per default leeg/negatief.
     */
    private fun scriptDiscovery(seedEventsJson: String, dateLookupJson: (String) -> String = { "{}" }) {
        openAi.onAction(ExternalCall.ACTION_EVENT_DISCOVERY) { call ->
            when {
                call.subject.orEmpty().startsWith("Seed-event") -> seedEventsJson
                call.subject.orEmpty().startsWith("Datum-lookup") -> dateLookupJson(call.subject.orEmpty())
                else -> "[]" // de "similar"-call: geen extra voorstellen
            }
        }
    }

    private fun discoveryCalls(user: TestUser): Int =
        openAi.callsFor(ExternalCall.ACTION_EVENT_DISCOVERY, user.username).size

    @Test
    fun `discovery-run maakt event aan inclusief aankondigings-feed-item`() {
        val user = registerEventUser("TestConf")
        serveTavilySearch()
        scriptDiscovery(
            """[{"id":"testconf-2026","name":"TestConf 2026","organization":"TestOrg",
                 "startDate":"$startDate","endDate":"$endDate","location":"Amsterdam",
                 "description":"Conferentie over Kotlin en Spring.","sourceLinks":["https://testconf.example/2026"]}]"""
        )

        assertEquals(0, getJson("/api/events", user.token).size())
        val resp = post("/api/events/discover", user.token)
        assertEquals(200, resp.status)
        assertEquals("ok", resp.json(mapper).path("status").asText())

        await { getJson("/api/events", user.token).size() == 1 }
        val ev = getJson("/api/events", user.token)[0]
        assertEquals("testconf-2026", ev.path("id").asText())
        assertEquals("TestConf 2026", ev.path("name").asText())
        assertEquals("TestOrg", ev.path("organization").asText())
        assertEquals("$startDate", ev.path("startDate").asText())
        assertEquals("$endDate", ev.path("endDate").asText())
        assertEquals("Amsterdam", ev.path("location").asText())
        assertTrue(ev.path("feedItemId").asText().isNotBlank())

        // GET op id werkt; onbekend id geeft 404.
        assertEquals(200, get("/api/events/testconf-2026", user.token).status)
        assertEquals(404, get("/api/events/bestaat-niet", user.token).status)

        // Het aankondigings-feed-item staat in de feed, in het Nederlands.
        val feed = getJson("/api/feed", user.token)
        assertEquals(1, feed.size())
        assertEquals(ev.path("feedItemId").asText(), feed[0].path("id").asText())
        assertEquals("TestConf 2026", feed[0].path("title").asText())
        // De aankondiging bevat de startdatum in NL-formaat ("1 september 2026").
        val dutchDate = "${startDate.dayOfMonth} " +
            "${startDate.month.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("nl"))} ${startDate.year}"
        assertTrue(
            feed[0].path("shortSummary").asText().contains(dutchDate),
            "shortSummary mist '$dutchDate': ${feed[0].path("shortSummary").asText()}"
        )
        assertTrue(feed[0].path("feedReason").asText().contains("Automatisch ontdekt tech-event"))
    }

    @Test
    fun `tweede discovery-run dedupt - geen dubbel event en geen tweede aankondiging`() {
        val user = registerEventUser("TestConf")
        serveTavilySearch()
        scriptDiscovery(
            """[{"id":"testconf-2026","name":"TestConf 2026","organization":"TestOrg",
                 "startDate":"$startDate","endDate":null,"location":"Amsterdam",
                 "description":"Beschrijving.","sourceLinks":[]}]"""
        )

        post("/api/events/discover", user.token)
        await { getJson("/api/events", user.token).size() == 1 }
        val callsNaRun1 = discoveryCalls(user)

        post("/api/events/discover", user.token)
        // Run 2 = nogmaals seed-extract + similar-call; wacht tot die geweest zijn.
        await { discoveryCalls(user) >= callsNaRun1 + 2 }
        Thread.sleep(1000)

        assertEquals(1, getJson("/api/events", user.token).size())
        assertEquals(1, getJson("/api/feed", user.token).size())
    }

    @Test
    fun `event verwijderen zet het op de denylist en de volgende run slaat het over`() {
        val user = registerEventUser("TestConf")
        serveTavilySearch()
        scriptDiscovery(
            """[{"id":"testconf-2026","name":"TestConf 2026","organization":null,
                 "startDate":"$startDate","endDate":null,"location":"Amsterdam",
                 "description":"Beschrijving.","sourceLinks":[]}]"""
        )

        post("/api/events/discover", user.token)
        await { getJson("/api/events", user.token).size() == 1 }
        assertEquals(1, getJson("/api/feed", user.token).size())

        // DELETE: event weg, gekoppeld feed-item weg, denylist-entry erbij.
        val deleted = delete("/api/events/testconf-2026", user.token)
        assertEquals(200, deleted.status)
        assertEquals("ok", deleted.json(mapper).path("status").asText())
        assertEquals(0, getJson("/api/events", user.token).size())
        assertEquals(0, getJson("/api/feed", user.token).size())

        val entries = getJson("/api/settings/event-denylist", user.token).path("entries")
        assertEquals(1, entries.size())
        assertEquals("testconf-2026", entries[0].path("normalizedId").asText())
        assertEquals("TestConf 2026", entries[0].path("name").asText())

        // Volgende run ontdekt hetzelfde event opnieuw maar slaat het over.
        val callsVoorRun2 = discoveryCalls(user)
        post("/api/events/discover", user.token)
        await { discoveryCalls(user) >= callsVoorRun2 + 2 }
        Thread.sleep(1000)
        assertEquals(0, getJson("/api/events", user.token).size())
        assertEquals(0, getJson("/api/feed", user.token).size())
    }

    @Test
    fun `event zonder datum krijgt een extra datum-lookup en wordt zonder datum verworpen`() {
        val user = registerEventUser("DateConf")
        serveTavilySearch()
        // Twee kandidaten zonder datum: voor GoedConf levert de extra
        // datum-lookup wél een datum op, voor VaagConf niet.
        scriptDiscovery(
            seedEventsJson = """[
                {"id":"goedconf-2026","name":"GoedConf 2026","organization":null,
                 "startDate":null,"endDate":null,"location":"Utrecht","description":"Met datum vindbaar.","sourceLinks":[]},
                {"id":"vaagconf-2026","name":"VaagConf 2026","organization":null,
                 "startDate":null,"endDate":null,"location":"Ergens","description":"Zonder vindbare datum.","sourceLinks":[]}
            ]""",
            dateLookupJson = { subject ->
                if (subject.contains("GoedConf")) {
                    """{"startDate":"$startDate","endDate":"$endDate"}"""
                } else {
                    """{"startDate":null,"endDate":null}"""
                }
            }
        )

        post("/api/events/discover", user.token)

        await { getJson("/api/events", user.token).size() == 1 }
        // Even wachten of VaagConf niet alsnog binnenkomt.
        Thread.sleep(1000)
        val events = getJson("/api/events", user.token)
        assertEquals(1, events.size())
        assertEquals("goedconf-2026", events[0].path("id").asText())
        assertEquals("$startDate", events[0].path("startDate").asText())
        assertEquals("$endDate", events[0].path("endDate").asText())
        assertFalse(events.any { it.path("id").asText() == "vaagconf-2026" })

        // Alleen het geaccepteerde event kreeg een aankondiging.
        assertEquals(1, getJson("/api/feed", user.token).size())
    }

    @Test
    fun `events-endpoints weigeren zonder token`() {
        for ((method, path) in listOf(
            "GET" to "/api/events",
            "POST" to "/api/events/discover",
            "DELETE" to "/api/events/some-id"
        )) {
            val resp = request(method, path, if (method == "POST") "{}" else null, token = null)
            assertTrue(resp.status in listOf(401, 403), "$method $path: verwachtte 401/403, kreeg ${resp.status}")
        }
    }
}
