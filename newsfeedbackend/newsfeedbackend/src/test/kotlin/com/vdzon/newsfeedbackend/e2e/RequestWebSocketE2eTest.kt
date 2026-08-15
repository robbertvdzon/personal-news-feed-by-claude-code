package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import tools.jackson.databind.JsonNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.http.WebSocketHandshakeException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException

/**
 * Het WebSocket-endpoint `/ws/requests` door de echte app heen: de
 * geauthenticeerde handshake (`?token=<jwt>`), `serverVersion` bij connect
 * (alleen naar de verbindende client) en de levering van het volledige
 * [com.vdzon.newsfeedbackend.request.NewsRequest]-object bij elke
 * statuswijziging — uitsluitend aan de eigenaar van het verzoek.
 *
 * Legt vast wat `specs/backend-functional-spec.md` (§5, WebSocket) en
 * `specs/frontend-spec.md` (§ WebSocket-integratie) beloven — de REST-kant
 * is via [RequestsE2eTest] gedekt, deze kant tot nu toe niet.
 *
 * Synchronisatie: er wordt altijd eerst verbonden en op het
 * `serverVersion`-bericht gewacht (het anker dat de sessie server-side
 * geregistreerd is) vóór er een statuswijziging wordt uitgelokt. Nergens een
 * vaste sleep; de enige korte time-out is de negatieve assertie in
 * `serverVersion wordt niet gebroadcast naar bestaande verbindingen`.
 */
class RequestWebSocketE2eTest : E2eTestBase() {

    companion object {
        /** Zonder api-key doet de TavilyClient geen HTTP-call; patroon [RequestsE2eTest]. */
        @JvmStatic
        @DynamicPropertySource
        fun tavilyKey(registry: DynamicPropertyRegistry) {
            registry.add("app.tavily.api-key") { "e2e-test-key" }
        }
    }

    /** Royale time-out voor berichten die er echt horen te komen. */
    private val messageTimeout = Duration.ofSeconds(20)

    /** Bewust kort: alleen gebruikt waar "er komt niets" het verwachte resultaat is. */
    private val silenceTimeout = Duration.ofSeconds(2)

    private val clients = CopyOnWriteArrayList<WsTestClient>()

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun connect(user: TestUser): WsTestClient =
        WsTestClient.connect(port, mapper, user.token).also { clients.add(it) }

    /** Verbindt en consumeert het `serverVersion`-bericht als connect-anker. */
    private fun connectAndAwaitServerVersion(user: TestUser): WsTestClient {
        val client = connect(user)
        val version = client.poll(messageTimeout) ?: fail("geen serverVersion-bericht na connect")
        assertEquals("serverVersion", version.path("type").asString())
        return client
    }

    private fun createBody(subject: String) = """
        {"subject": "$subject", "sourceItemId": null, "sourceItemTitle": null,
         "preferredCount": 1, "maxCount": 1, "extraInstructions": "", "maxAgeDays": 3}
    """.trimIndent()

    /** Serveert één Tavily-zoekresultaat plus de bijbehorende extract-tekst. */
    private fun serveTavily() {
        val result = FakeContentServer.TavilyTestSearchResult(
            title = "Artikel 1",
            url = "https://nieuws.example/artikel-1",
            content = "Snippet van artikel 1",
            publishedDate = "2026-07-01T08:00:00"
        )
        content.serve("/tavily/search", "application/json", content.tavilySearchJson(listOf(result)))
        content.serve(
            "/tavily/extract", "application/json",
            content.tavilyExtractJson(mapOf(result.url to "Volledige tekst van ${result.title}."))
        )
    }

    /** Maakt een ad-hoc verzoek aan dat via de fakes gegarandeerd DONE wordt. */
    private fun createAdhocRequest(user: TestUser, subject: String): String {
        serveTavily()
        openAi.onAction(ExternalCall.ACTION_ADHOC_SUMMARIZE) { "Fake samenvatting voor de websocket-e2e-test." }
        val created = post("/api/requests", user.token, createBody(subject))
        assertEquals(201, created.status)
        return created.json(mapper).path("id").asString()
    }

    /**
     * Verzamelt berichten tot het verzoek [id] op DONE staat. Er wordt per `id`
     * gefilterd omdat de hardcoded `RssScheduler`-cron bij een run precies over
     * het hele uur een `hourly-update-*`-broadcast tussendoor kan zetten.
     */
    private fun collectUntilDone(client: WsTestClient, id: String): List<JsonNode> {
        val messages = mutableListOf<JsonNode>()
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (System.nanoTime() < deadline) {
            val node = client.poll(messageTimeout) ?: continue
            messages.add(node)
            if (node.path("id").asString() == id && node.path("status").asString() == "DONE") return messages
        }
        fail("geen DONE-bericht voor request $id; wel ontvangen: $messages")
    }

    @Test
    fun `direct na connect komt precies een serverVersion-bericht`() {
        val user = registerUser("ws")
        val client = connect(user)

        val version = client.poll(messageTimeout) ?: fail("geen serverVersion-bericht na connect")
        assertEquals("serverVersion", version.path("type").asString())
        // BUILD_SHA/BUILD_TIME worden alleen in de Docker-image gezet; in de
        // testomgeving geldt dus de gedocumenteerde fallback.
        assertEquals("unknown", version.path("sha").asString())
        assertEquals("unknown", version.path("buildTime").asString())

        // Precies één: er volgt geen tweede versiebericht.
        assertNull(client.poll(silenceTimeout), "onverwacht tweede bericht na serverVersion")
    }

    @Test
    fun `serverVersion gaat alleen naar de verbindende client en wordt niet gebroadcast`() {
        val user = registerUser("ws")
        val first = connectAndAwaitServerVersion(user)

        val second = connect(user)
        assertEquals("serverVersion", (second.poll(messageTimeout) ?: fail("B kreeg geen serverVersion")).path("type").asString())

        // Spec: "alleen naar de verbindende client, geen broadcast" — A hoort
        // van de connect van B niets te merken.
        assertNull(first.poll(silenceTimeout), "verbinding A kreeg een tweede serverVersion")
    }

    @Test
    fun `statuswijzigingen van een verzoek komen als volledige NewsRequest-objecten binnen`() {
        val user = registerUser("ws")
        val client = connectAndAwaitServerVersion(user)

        val id = createAdhocRequest(user, "Kotlin nieuws")
        val messages = collectUntilDone(client, id)

        // Ná serverVersion heeft géén enkel bericht een type-veld; clients
        // onderscheiden de twee soorten precies zo.
        assertTrue(messages.none { it.has("type") }, "onverwacht type-veld in een NewsRequest-bericht: $messages")

        val ofRequest = messages.filter { it.path("id").asString() == id }
        assertTrue(ofRequest.isNotEmpty(), "geen enkel bericht voor request $id")
        assertEquals("DONE", ofRequest.last().path("status").asString())
        assertEquals(
            listOf("PENDING", "PROCESSING", "DONE"),
            ofRequest.map { it.path("status").asString() }.distinct(),
            "onverwachte statusvolgorde: $ofRequest"
        )

        // Het volledige object gaat over de lijn, zodat NewsRequest.fromJson
        // aan de clientkant er echt op kan bouwen (er is geen DTO-laag, dus
        // deze serialisatie wordt nergens anders gedekt).
        val done = ofRequest.last()
        assertEquals(id, done.path("id").asString())
        assertEquals("Kotlin nieuws", done.path("subject").asString())
        assertEquals("DONE", done.path("status").asString())
        assertTrue(done.has("newItemCount"), "newItemCount ontbreekt: $done")
        assertEquals(1, done.path("newItemCount").asInt())
    }

    @Test
    fun `statusberichten gaan alleen naar de eigenaar en niet naar een andere gebruiker`() {
        val alice = registerUser("wsa")
        val bob = registerUser("wsb")
        val aliceClient = connectAndAwaitServerVersion(alice)
        val bobClient = connectAndAwaitServerVersion(bob)

        val id = createAdhocRequest(alice, "Nieuws alleen voor A")

        // A is de eigenaar en ziet de volledige reeks.
        val ofRequest = collectUntilDone(aliceClient, id)
            .filter { it.path("id").asString() == id }
        assertEquals(
            listOf("PENDING", "PROCESSING", "DONE"),
            ofRequest.map { it.path("status").asString() }.distinct(),
            "onverwachte statusvolgorde bij de eigenaar: $ofRequest"
        )

        // B hoort er niets van te merken: de server levert per eigenaar. A
        // heeft DONE al gezien, dus een ongefilterde broadcast had hier
        // allang in B's queue gestaan; de extra marge dekt nawerk af.
        val bijBob = mutableListOf<JsonNode>()
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            val node = bobClient.poll(silenceTimeout) ?: continue
            if (node.path("id").asString() == id) bijBob.add(node)
        }
        assertTrue(bijBob.isEmpty(), "gebruiker B kreeg berichten over het verzoek van A: $bijBob")
    }

    @Test
    fun `een handshake zonder token of met een onzin-token wordt geweigerd`() {
        // Er komt geen sessie tot stand, dus geen close-code: de JDK-client
        // ziet de 401 als een falende handshake-future.
        assertEquals(401, handshakeFailureStatus(null), "handshake zonder token werd niet geweigerd")
        assertEquals(401, handshakeFailureStatus("dit-is-geen-jwt"), "handshake met onzin-token werd niet geweigerd")
    }

    /** Verwacht dat de handshake met [token] faalt en geeft de HTTP-status terug. */
    private fun handshakeFailureStatus(token: String?): Int {
        val thrown = assertThrows(ExecutionException::class.java) {
            WsTestClient.connect(port, mapper, token)
        }
        val cause = thrown.cause
        assertTrue(
            cause is WebSocketHandshakeException,
            "verwachtte een geweigerde handshake, kreeg: $cause"
        )
        return (cause as WebSocketHandshakeException).response.statusCode()
    }

    @Test
    fun `een gesloten verbinding blokkeert de broadcast naar de overige clients niet`() {
        val user = registerUser("ws")
        val first = connectAndAwaitServerVersion(user)
        val second = connectAndAwaitServerVersion(user)

        first.close()

        val id = createAdhocRequest(user, "Nieuws na disconnect")
        val messages = collectUntilDone(second, id)

        assertNotNull(messages.firstOrNull { it.path("id").asString() == id })
        assertEquals("DONE", messages.last { it.path("id").asString() == id }.path("status").asString())
    }
}
