package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate

/**
 * De event-video-discovery-flow (KAN-66) door de echte app heen:
 * POST /api/events/videos/discover → [com.vdzon.newsfeedbackend.events.domain.EventVideoDiscoveryPipeline]
 * draait async per event binnen het discovery-window → Tavily-search
 * (fake-server) + AI-extractie (fake OpenAI, action
 * `event_video_discovery`, herkenbaar aan `call.subject` dat begint met
 * "Video's voor event ") → video's in GET /api/events/{id}/videos.
 */
class EventVideosE2eTest : E2eTestBase() {

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

    @Autowired
    private lateinit var eventRepository: EventRepository

    // Dynamische (toekomstige) datum zodat het event altijd binnen het
    // discovery-window valt, hoe laat deze test ook draait.
    private val startDate: LocalDate = LocalDate.now().plusMonths(2)
    private val endDate: LocalDate = startDate.plusDays(2)

    /**
     * Registreert een user met precies één event-voorkeur en zonder
     * actieve categorieën, zodat een discovery-run deterministisch is.
     */
    private fun registerEventUser(preference: String): TestUser {
        val user = registerUser("eventvideos")
        assertEquals(200, put("/api/settings", user.token, "[]").status)
        assertEquals(
            200,
            put("/api/settings/event-preferences", user.token, """{"names": ["$preference"]}""").status
        )
        return user
    }

    /** Discovert precies één event ("testconf-2026") binnen het window voor deze user. */
    private fun discoverOneEvent(user: TestUser) {
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
        openAi.onAction(ExternalCall.ACTION_EVENT_DISCOVERY) { call ->
            when {
                call.subject.orEmpty().startsWith("Seed-event") ->
                    """[{"id":"testconf-2026","name":"TestConf 2026","organization":"TestOrg",
                         "startDate":"$startDate","endDate":"$endDate","location":"Amsterdam",
                         "description":"Conferentie over Kotlin en Spring.","sourceLinks":["https://testconf.example/2026"]}]"""
                else -> "[]" // datum-lookup / similar-call: niet nodig hier
            }
        }
        post("/api/events/discover", user.token)
        await { getJson("/api/events", user.token).size() == 1 }
        // Fake-server/handlers weer schoon voor de video-discovery-scripting hieronder.
        openAi.reset()
        content.reset()
    }

    /** Script Tavily-zoekresultaten voor de video-search van het event. */
    private fun serveTavilyVideoSearch() {
        content.serve(
            "/tavily/search", "application/json",
            content.tavilySearchJson(
                listOf(
                    FakeContentServer.TavilyTestSearchResult(
                        title = "TestConf 2026 - Keynote",
                        url = "https://www.youtube.com/watch?v=abc123",
                        content = "Opname van de keynote van TestConf 2026."
                    )
                )
            )
        )
    }

    /** Script het fake-OpenAI-antwoord voor de video-extractie-actie. */
    private fun scriptVideoDiscovery(responseJson: String) {
        openAi.onAction(ExternalCall.ACTION_EVENT_VIDEO_DISCOVERY) { call ->
            assertTrue(
                call.subject.orEmpty().startsWith("Video's voor event "),
                "onverwacht subject voor video-discovery-call: ${call.subject}"
            )
            responseJson
        }
    }

    private fun videoDiscoveryCalls(user: TestUser): Int =
        openAi.callsFor(ExternalCall.ACTION_EVENT_VIDEO_DISCOVERY, user.username).size

    @Test
    fun `video-discovery-run vindt en bewaart de ontdekte video's van een event`() {
        val user = registerEventUser("TestConf")
        discoverOneEvent(user)

        serveTavilyVideoSearch()
        scriptVideoDiscovery(
            """[{"title":"Keynote: TestConf 2026","url":"https://www.youtube.com/watch?v=abc123",
                 "description":"Nederlandse samenvatting van de keynote."}]"""
        )

        assertEquals(0, getJson("/api/events/testconf-2026/videos", user.token).size())
        val resp = post("/api/events/videos/discover", user.token)
        assertEquals(200, resp.status)
        assertEquals("ok", resp.json(mapper).path("status").asText())

        await { getJson("/api/events/testconf-2026/videos", user.token).size() == 1 }
        val video = getJson("/api/events/testconf-2026/videos", user.token)[0]
        assertEquals("Keynote: TestConf 2026", video.path("title").asText())
        assertEquals("https://www.youtube.com/watch?v=abc123", video.path("videoUrl").asText())
        assertEquals("Nederlandse samenvatting van de keynote.", video.path("descriptionNl").asText())
    }

    @Test
    fun `tweede video-discovery-run dedupt op canonieke url en werkt de video bij`() {
        val user = registerEventUser("TestConf")
        discoverOneEvent(user)

        serveTavilyVideoSearch()
        scriptVideoDiscovery(
            """[{"title":"Keynote: TestConf 2026","url":"https://www.youtube.com/watch?v=abc123",
                 "description":"Eerste beschrijving."}]"""
        )
        post("/api/events/videos/discover", user.token)
        await { getJson("/api/events/testconf-2026/videos", user.token).size() == 1 }

        // Tweede run: zelfde video (trailing slash + fragment = triviale
        // variatie op de canonieke URL), maar gewijzigde titel/beschrijving.
        scriptVideoDiscovery(
            """[{"title":"Keynote: TestConf 2026 (bijgewerkt)","url":"https://www.youtube.com/watch?v=abc123#t=10",
                 "description":"Bijgewerkte beschrijving."}]"""
        )
        val callsVoorRun2 = videoDiscoveryCalls(user)
        post("/api/events/videos/discover", user.token)
        await { videoDiscoveryCalls(user) >= callsVoorRun2 + 1 }
        await {
            getJson("/api/events/testconf-2026/videos", user.token)
                .firstOrNull()?.path("title")?.asText() == "Keynote: TestConf 2026 (bijgewerkt)"
        }

        val videos = getJson("/api/events/testconf-2026/videos", user.token)
        assertEquals(1, videos.size())
        assertEquals("Keynote: TestConf 2026 (bijgewerkt)", videos[0].path("title").asText())
        assertEquals("Bijgewerkte beschrijving.", videos[0].path("descriptionNl").asText())
        assertEquals("https://www.youtube.com/watch?v=abc123", videos[0].path("videoUrl").asText())
    }

    @Test
    fun `event buiten het discovery-window wordt overgeslagen - geen tavily-call, geen videos`() {
        val user = registerEventUser("TestConf")
        // Event rechtstreeks geseed met een startDate > 1 jaar terug —
        // de normale discovery-flow accepteert zulke events niet (KAN-68),
        // dus dit event wordt direct via de repository aangemaakt.
        val oldEvent = Event(
            id = "oudconf-2020",
            name = "OudConf 2020",
            organization = "OudOrg",
            startDate = "${LocalDate.now().minusYears(2)}",
            endDate = null,
            location = "Rotterdam",
            description = "Een oud event, ver buiten het window.",
            sourceLinks = emptyList()
        )
        eventRepository.upsert(user.username, oldEvent)
        assertEquals(1, getJson("/api/events", user.token).size())

        // Bewust NIET scripten: als de pipeline hier toch een Tavily-call
        // voor zou doen, komt er een lege response terug (server geeft 404)
        // en zou de fake-OpenAI (onbeantwoord "{}") sowieso geen video's
        // opleveren — beide voorkomen dat er onterecht video's ontstaan.
        val resp = post("/api/events/videos/discover", user.token)
        assertEquals(200, resp.status)

        // Wacht op zoveel mogelijk voortgang van de (mogelijk lege) run en
        // controleer dat er geen video-discovery-call voor deze user is
        // gelogd en dat er geen video's zijn opgeslagen.
        Thread.sleep(2000)
        assertEquals(0, videoDiscoveryCalls(user))
        assertEquals(0, getJson("/api/events/oudconf-2020/videos", user.token).size())
    }

    @Test
    fun `video-discovery-endpoint weigert zonder token`() {
        val resp = request("POST", "/api/events/videos/discover", "{}", token = null)
        assertTrue(resp.status in listOf(401, 403), "verwachtte 401/403, kreeg ${resp.status}")
    }
}
