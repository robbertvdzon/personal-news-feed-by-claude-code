package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * De DevTalk-podcast-generatie door de echte app heen:
 * POST /api/podcasts → [com.vdzon.newsfeedbackend.podcast.domain.PodcastGenerator]
 * draait async → AI-script (action `podcast_script`) + AI-topics
 * (`podcast_topics`) via de fake OpenAI → TTS per dialoogregel via HTTP
 * naar de fake-server (OpenAI `/v1/audio/speech` of ElevenLabs
 * `/v1/text-to-speech/{voice}`) → status DONE → audio-endpoint.
 *
 * Buiten scope (bewust): de vertaalflow ([com.vdzon.newsfeedbackend.podcast.domain.PodcastTranslator])
 * — die hangt af van een lokale ffmpeg-installatie voor MP3-concatenatie
 * en is daarmee niet betrouwbaar te faken in deze suite.
 */
class PodcastGenerationE2eTest : E2eTestBase() {

    companion object {
        /**
         * De TtsClient doet zonder api-key geen HTTP-call (returnt direct
         * null); met dummy keys komen de TTS-calls echt bij de
         * [FakeContentServer] uit.
         */
        @JvmStatic
        @DynamicPropertySource
        fun ttsKeys(registry: DynamicPropertyRegistry) {
            registry.add("app.openai.api-key") { "e2e-openai-key" }
            registry.add("app.elevenlabs.api-key") { "e2e-eleven-key" }
        }
    }

    // 4 dialoogregels (I/G/I/G) — het canonieke format uit PodcastScriptParserTest.
    private val script = """
        INTERVIEWER: Welkom bij DevTalk.
        GAST: Dank je, leuk om hier te zijn.
        INTERVIEWER: Wat is recent het belangrijkste nieuws?
        GAST: Kotlin 2.3 is uitgebracht met een sneller compilerbackend.
    """.trimIndent()

    private fun scriptAi() {
        openAi.onAction(ExternalCall.ACTION_PODCAST_SCRIPT) { script }
        openAi.onAction(ExternalCall.ACTION_PODCAST_TOPICS) { """["Kotlin 2.3", "Spring Boot 4"]""" }
    }

    private fun createBody(provider: String = "OPENAI") =
        """{"periodDays": 7, "durationMinutes": 1, "customTopics": ["Kotlin"], "ttsProvider": "$provider"}"""

    private fun statusOf(user: TestUser, id: String): String =
        getJson("/api/podcasts", user.token).first { it.path("id").asText() == id }.path("status").asText()

    // Binaire GET voor het audio-endpoint (de tekst-helper van de base
    // zou de MP3-bytes door de String-decodering heen verminken).
    private val binaryHttp: HttpClient = HttpClient.newHttpClient()
    private fun getBytes(path: String, token: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(15))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return binaryHttp.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun `happy path - podcast genereren tot DONE en audio streamen inclusief JWT via query-param`() {
        val user = registerUser("podcast")
        scriptAi()
        val segmentBytes = "FAKE-OPENAI-MP3-SEGMENT".toByteArray()
        content.serveBytes("/openai/v1/audio/speech", "audio/mpeg", segmentBytes)

        val created = post("/api/podcasts", user.token, createBody("OPENAI"))
        assertEquals(201, created.status)
        val id = created.json(mapper).path("id").asText()
        assertEquals("PENDING", created.json(mapper).path("status").asText())
        assertEquals(1, created.json(mapper).path("podcastNumber").asInt())

        await { statusOf(user, id) == "DONE" }

        // Detail-view: titel uit de topics, script en metadata aanwezig.
        val detail = getJson("/api/podcasts/$id", user.token)
        assertTrue(detail.path("title").asText().startsWith("DevTalk 1, "))
        assertTrue(detail.path("title").asText().endsWith("— Kotlin 2.3, Spring Boot 4"))
        assertEquals(listOf("Kotlin 2.3", "Spring Boot 4"), detail.path("topics").map { it.asText() })
        assertEquals(script, detail.path("scriptText").asText())
        assertEquals(60, detail.path("durationSeconds").asInt())
        // De lijst-view stript het (potentieel lange) script bewust.
        val inList = getJson("/api/podcasts", user.token).first { it.path("id").asText() == id }
        assertTrue(inList.path("scriptText").isNull)

        // Het custom topic zat in de script-prompt.
        val scriptCalls = openAi.callsFor(ExternalCall.ACTION_PODCAST_SCRIPT, user.username)
        assertEquals(1, scriptCalls.size)
        assertTrue(scriptCalls[0].user.contains("Onderwerpen: Kotlin"))

        // Audio met Bearer-token: 4 dialoogregels → 4 aaneengeplakte TTS-segmenten.
        val audio = getBytes("/api/podcasts/$id/audio", user.token)
        assertEquals(200, audio.statusCode())
        assertEquals(4 * segmentBytes.size, audio.body().size)
        assertEquals("audio/mpeg", audio.headers().firstValue("Content-Type").orElse(""))
        val inlineDisposition = audio.headers().firstValue("Content-Disposition").orElse("")
        assertTrue(inlineDisposition.startsWith("inline"))
        assertTrue(inlineDisposition.contains(".mp3"))

        // Zonder token: geweigerd. Met het JWT als query-param: toegestaan
        // (voor audio-players die geen headers kunnen zetten).
        assertTrue(getBytes("/api/podcasts/$id/audio").statusCode() in listOf(401, 403))
        val viaQuery = getBytes("/api/podcasts/$id/audio?token=${user.token}")
        assertEquals(200, viaQuery.statusCode())
        assertArrayEquals(audio.body(), viaQuery.body())

        // download=1 → attachment-disposition voor echte downloads.
        val download = getBytes("/api/podcasts/$id/audio?download=true", user.token)
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"))
    }

    @Test
    fun `elevenlabs-provider gebruikt beide stemmen en stript de ID3-tag per segment`() {
        val user = registerUser("podcast")
        scriptAi()
        val interviewerAudio = "INTERVIEWER-AUDIO".toByteArray()
        val guestAudio = "GUEST-AUDIO-BYTES".toByteArray()
        // Voice-id's zijn de defaults uit application.properties.
        content.serveBytes(
            "/elevenlabs/v1/text-to-speech/Jn7U4vF8ZkmjZIZRn4Uk", "audio/mpeg", withId3Tag(interviewerAudio)
        )
        content.serveBytes(
            "/elevenlabs/v1/text-to-speech/h6uBOiAjLKklte8hdYio", "audio/mpeg", withId3Tag(guestAudio)
        )

        val id = post("/api/podcasts", user.token, createBody("ELEVENLABS"))
            .json(mapper).path("id").asText()
        await { statusOf(user, id) == "DONE" }

        // Script is I/G/I/G → audio = interviewer+gast+interviewer+gast,
        // telkens met de ID3-header van het segment eraf gestript.
        val audio = getBytes("/api/podcasts/$id/audio", user.token)
        assertEquals(200, audio.statusCode())
        assertArrayEquals(
            interviewerAudio + guestAudio + interviewerAudio + guestAudio,
            audio.body()
        )
    }

    @Test
    fun `script zonder herkenbare sprekerlabels leidt tot status FAILED zonder audio`() {
        val user = registerUser("podcast")
        openAi.onAction(ExternalCall.ACTION_PODCAST_SCRIPT) {
            "Welkom bij de podcast.\nVandaag bespreken we AI.\nDat was het weer."
        }
        openAi.onAction(ExternalCall.ACTION_PODCAST_TOPICS) { """["AI"]""" }
        // TTS staat klaar maar mag nooit aangeroepen worden.
        content.serveBytes("/openai/v1/audio/speech", "audio/mpeg", "X".toByteArray())

        val id = post("/api/podcasts", user.token, createBody("OPENAI"))
            .json(mapper).path("id").asText()

        await { statusOf(user, id) == "FAILED" }
        assertEquals(404, getBytes("/api/podcasts/$id/audio", user.token).statusCode())
    }

    @Test
    fun `falende TTS leidt tot status FAILED en de podcast is daarna verwijderbaar`() {
        val user = registerUser("podcast")
        scriptAi()
        // Bewust géén /openai/v1/audio/speech geserveerd: elke TTS-call
        // krijgt een 404 van de fake-server → renderAudio levert niets op.

        val id = post("/api/podcasts", user.token, createBody("OPENAI"))
            .json(mapper).path("id").asText()

        await { statusOf(user, id) == "FAILED" }
        assertEquals(404, getBytes("/api/podcasts/$id/audio", user.token).statusCode())

        assertEquals(204, delete("/api/podcasts/$id", user.token).status)
        assertTrue(getJson("/api/podcasts", user.token).none { it.path("id").asText() == id })
        assertEquals(404, get("/api/podcasts/$id", user.token).status)
        // Onbekend id op delete geeft ook een nette 404.
        assertEquals(404, delete("/api/podcasts/bestaat-niet", user.token).status)
    }

    /**
     * Verpakt [payload] achter een minimale ID3v2.3-header met een
     * tag-body van 10 junk-bytes — precies wat [com.vdzon.newsfeedbackend.podcast.infrastructure.TtsClient.stripId3]
     * eraf hoort te halen.
     */
    private fun withId3Tag(payload: ByteArray): ByteArray {
        val tagBodySize = 10
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            3, 0, // versie 2.3.0
            0, // flags
            0, 0, 0, tagBodySize.toByte() // syncsafe size
        )
        return header + ByteArray(tagBodySize) { 0x7f } + payload
    }
}
