package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.ai.AiResponse
import com.vdzon.newsfeedbackend.ai.SpeechResponse
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * De DevTalk-podcast-generatie door de echte app heen:
 * POST /api/podcasts → [com.vdzon.newsfeedbackend.podcast.domain.PodcastGenerator]
 * draait async → agent-job voor script + topics (action `podcast_script`)
 * → één TTS-job met alle sprekerbeurten (`podcast_tts` of
 * `podcast_tts_elevenlabs`) via de fake Agent Runtime-client → status DONE
 * → audio-endpoint.
 */
class PodcastGenerationE2eTest : E2eTestBase() {

    private val turns = listOf(
        "INTERVIEWER" to "Welkom bij DevTalk.",
        "GAST" to "Dank je, leuk om hier te zijn.",
        "INTERVIEWER" to "Wat is recent het belangrijkste nieuws?",
        "GAST" to "Kotlin 2.3 is uitgebracht met een sneller compilerbackend."
    )
    private val script = turns.joinToString("\n") { "${it.first}: ${it.second}" }

    private fun scriptAi() {
        ai.onAction(ExternalCall.ACTION_PODCAST_SCRIPT) {
            turns.joinToString(prefix = """{"topics": ["Kotlin 2.3", "Spring Boot 4"], "turns": [""", postfix = "]}") {
                """{"speaker": "${it.first}", "text": "${it.second}"}"""
            }
        }
    }

    private fun createBody(provider: String = "OPENAI") =
        """{"periodDays": 7, "durationMinutes": 1, "customTopics": ["Kotlin"], "ttsProvider": "$provider"}"""

    private fun statusOf(user: TestUser, id: String): String =
        getJson("/api/podcasts", user.token).first { it.path("id").asString() == id }.path("status").asString()

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
        val audioBytes = "FAKE-OPENAI-MP3".toByteArray()
        ai.speechHandler = { SpeechResponse(audioBytes, AiResponse.STATUS_OK) }

        val created = post("/api/podcasts", user.token, createBody("OPENAI"))
        assertEquals(201, created.status)
        val id = created.json(mapper).path("id").asString()
        assertEquals("PENDING", created.json(mapper).path("status").asString())
        assertEquals(1, created.json(mapper).path("podcastNumber").asInt())

        await { statusOf(user, id) == "DONE" }

        // Detail-view: titel uit de topics, script en metadata aanwezig.
        val detail = getJson("/api/podcasts/$id", user.token)
        assertTrue(detail.path("title").asString().startsWith("DevTalk 1, "))
        assertTrue(detail.path("title").asString().endsWith("— Kotlin 2.3, Spring Boot 4"))
        assertEquals(listOf("Kotlin 2.3", "Spring Boot 4"), detail.path("topics").values().map { it.asString() })
        assertEquals(script, detail.path("scriptText").asString())
        assertEquals(60, detail.path("durationSeconds").asInt())
        // De lijst-view stript het (potentieel lange) script bewust.
        val inList = getJson("/api/podcasts", user.token).first { it.path("id").asString() == id }
        assertTrue(inList.path("scriptText").isNull)

        // Het custom topic zat in de script-prompt.
        val scriptCalls = ai.callsFor(ExternalCall.ACTION_PODCAST_SCRIPT, user.username)
        assertEquals(1, scriptCalls.size)
        assertTrue(scriptCalls[0].prompt.contains("Onderwerpen: Kotlin"))

        // Eén TTS-job met 4 sprekerbeurten, OpenAI-stemmen per rol.
        val speech = ai.speechCalls.single()
        assertEquals(ExternalCall.ACTION_PODCAST_TTS, speech.action)
        assertEquals(listOf("onyx", "alloy", "onyx", "alloy"), speech.segments.map { it.voice })
        assertEquals(turns.map { it.second }, speech.segments.map { it.text })

        // Audio met Bearer-token: de MP3 uit de runtime.
        val audio = getBytes("/api/podcasts/$id/audio", user.token)
        assertEquals(200, audio.statusCode())
        assertArrayEquals(audioBytes, audio.body())
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
    fun `elevenlabs-provider gebruikt beide stemmen via de elevenlabs-actie`() {
        val user = registerUser("podcast")
        scriptAi()

        val id = post("/api/podcasts", user.token, createBody("ELEVENLABS"))
            .json(mapper).path("id").asString()
        await { statusOf(user, id) == "DONE" }

        // Voice-id's zijn de defaults uit application.properties.
        val speech = ai.speechCalls.single()
        assertEquals(ExternalCall.ACTION_PODCAST_TTS_ELEVENLABS, speech.action)
        assertEquals(
            listOf("Jn7U4vF8ZkmjZIZRn4Uk", "h6uBOiAjLKklte8hdYio", "Jn7U4vF8ZkmjZIZRn4Uk", "h6uBOiAjLKklte8hdYio"),
            speech.segments.map { it.voice }
        )
        assertArrayEquals(FakeAiClient.FAKE_AUDIO, getBytes("/api/podcasts/$id/audio", user.token).body())
    }

    @Test
    fun `script zonder sprekerbeurten leidt tot status FAILED zonder audio`() {
        val user = registerUser("podcast")
        ai.onAction(ExternalCall.ACTION_PODCAST_SCRIPT) { """{"topics": ["AI"], "turns": []}""" }

        val id = post("/api/podcasts", user.token, createBody("OPENAI"))
            .json(mapper).path("id").asString()

        await { statusOf(user, id) == "FAILED" }
        assertEquals(404, getBytes("/api/podcasts/$id/audio", user.token).statusCode())
        assertTrue(ai.speechCalls.isEmpty(), "zonder script hoort er geen TTS-job te starten")
    }

    @Test
    fun `falende TTS leidt tot status FAILED en de podcast is daarna verwijderbaar`() {
        val user = registerUser("podcast")
        scriptAi()
        ai.speechHandler = { SpeechResponse(null, AiResponse.STATUS_ERROR, "TTS-provider onbereikbaar") }

        val id = post("/api/podcasts", user.token, createBody("OPENAI"))
            .json(mapper).path("id").asString()

        await { statusOf(user, id) == "FAILED" }
        assertEquals(404, getBytes("/api/podcasts/$id/audio", user.token).statusCode())

        assertEquals(204, delete("/api/podcasts/$id", user.token).status)
        assertTrue(getJson("/api/podcasts", user.token).none { it.path("id").asString() == id })
        assertEquals(404, get("/api/podcasts/$id", user.token).status)
        // Onbekend id op delete geeft ook een nette 404.
        assertEquals(404, delete("/api/podcasts/bestaat-niet", user.token).status)
    }

}
