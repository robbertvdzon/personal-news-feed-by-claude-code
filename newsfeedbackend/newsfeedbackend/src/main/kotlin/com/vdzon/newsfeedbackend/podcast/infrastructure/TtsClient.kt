package com.vdzon.newsfeedbackend.podcast.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.podcast.TtsProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class TtsClient(
    @Value("\${app.openai.api-key:}") private val openaiKey: String,
    @Value("\${app.openai.base-url:https://api.openai.com}") private val openaiBaseUrl: String,
    @Value("\${app.elevenlabs.api-key:}") private val elevenKey: String,
    @Value("\${app.elevenlabs.base-url:https://api.elevenlabs.io}") private val elevenBaseUrl: String,
    @Value("\${app.elevenlabs.voice-interviewer:Jn7U4vF8ZkmjZIZRn4Uk}") private val voiceInterviewer: String,
    @Value("\${app.elevenlabs.voice-guest:h6uBOiAjLKklte8hdYio}") private val voiceGuest: String,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    fun generate(provider: TtsProvider, role: SpeakerRole, text: String): ByteArray? = when (provider) {
        TtsProvider.OPENAI -> openai(role, text)
        TtsProvider.ELEVENLABS -> eleven(role, text)
    }

    enum class SpeakerRole { INTERVIEWER, GUEST }

    private fun openai(role: SpeakerRole, text: String): ByteArray? {
        if (openaiKey.isBlank()) return null
        val voice = if (role == SpeakerRole.INTERVIEWER) "onyx" else "alloy"
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to "tts-1",
                "voice" to voice,
                "input" to text,
                "speed" to 1.2
            )
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$openaiBaseUrl/v1/audio/speech"))
            .header("Authorization", "Bearer $openaiKey")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() >= 400) {
                log.warn("[TTS] OpenAI -> {}", resp.statusCode())
                null
            } else resp.body()
        } catch (e: Exception) {
            log.warn("[TTS] OpenAI failed: {}", e.message)
            null
        }
    }

    private fun eleven(role: SpeakerRole, text: String): ByteArray? {
        if (elevenKey.isBlank()) return null
        val voiceId = if (role == SpeakerRole.INTERVIEWER) voiceInterviewer else voiceGuest
        val body = mapper.writeValueAsString(
            mapOf(
                "text" to text,
                "model_id" to "eleven_multilingual_v2",
                "voice_settings" to mapOf("stability" to 0.5, "similarity_boost" to 0.75)
            )
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$elevenBaseUrl/v1/text-to-speech/$voiceId"))
            .header("xi-api-key", elevenKey)
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() >= 400) {
                log.warn("[TTS] ElevenLabs -> {}", resp.statusCode())
                null
            } else stripId3(resp.body())
        } catch (e: Exception) {
            log.warn("[TTS] ElevenLabs failed: {}", e.message)
            null
        }
    }

    private fun stripId3(bytes: ByteArray): ByteArray {
        // Strip ID3v2 header at start (10 bytes header + size)
        if (bytes.size > 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val size = ((bytes[6].toInt() and 0x7f) shl 21) or
                ((bytes[7].toInt() and 0x7f) shl 14) or
                ((bytes[8].toInt() and 0x7f) shl 7) or
                (bytes[9].toInt() and 0x7f)
            val skip = 10 + size
            if (skip < bytes.size) return bytes.copyOfRange(skip, bytes.size)
        }
        return bytes
    }
}
