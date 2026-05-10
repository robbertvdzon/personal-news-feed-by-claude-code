package com.vdzon.newsfeedbackend.podcast.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.external_call.Pricing
import com.vdzon.newsfeedbackend.podcast.TtsProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class TtsClient(
    @Value("\${app.openai.api-key:}") private val openaiKey: String,
    @Value("\${app.openai.base-url:https://api.openai.com}") private val openaiBaseUrl: String,
    @Value("\${app.elevenlabs.api-key:}") private val elevenKey: String,
    @Value("\${app.elevenlabs.base-url:https://api.elevenlabs.io}") private val elevenBaseUrl: String,
    @Value("\${app.elevenlabs.voice-interviewer:Jn7U4vF8ZkmjZIZRn4Uk}") private val voiceInterviewer: String,
    @Value("\${app.elevenlabs.voice-guest:h6uBOiAjLKklte8hdYio}") private val voiceGuest: String,
    private val mapper: ObjectMapper,
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    fun generate(username: String, podcastId: String, provider: TtsProvider, role: SpeakerRole, text: String): ByteArray? {
        return when (provider) {
            TtsProvider.OPENAI -> openai(username, podcastId, role, text)
            TtsProvider.ELEVENLABS -> eleven(username, podcastId, role, text)
        }
    }

    enum class SpeakerRole { INTERVIEWER, GUEST }

    private fun openai(username: String, podcastId: String, role: SpeakerRole, text: String): ByteArray? {
        val started = Instant.now()
        val chars = text.length.toLong()
        val subj = "Podcast id=$podcastId role=${role.name}"
        if (openaiKey.isBlank()) {
            logTts(ExternalCall.PROVIDER_OPENAI, username, started, chars, 0.0, "error", "no API key", subj)
            return null
        }
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
                logTts(ExternalCall.PROVIDER_OPENAI, username, started, chars, 0.0,
                    "error", "http ${resp.statusCode()}", subj)
                null
            } else {
                logTts(ExternalCall.PROVIDER_OPENAI, username, started, chars,
                    Pricing.openaiTtsCost(chars), "ok", null, subj)
                resp.body()
            }
        } catch (e: Exception) {
            log.warn("[TTS] OpenAI failed: {}", e.message)
            logTts(ExternalCall.PROVIDER_OPENAI, username, started, chars, 0.0, "error", e.message, subj)
            null
        }
    }

    private fun eleven(username: String, podcastId: String, role: SpeakerRole, text: String): ByteArray? {
        val started = Instant.now()
        val chars = text.length.toLong()
        val subj = "Podcast id=$podcastId role=${role.name}"
        if (elevenKey.isBlank()) {
            logTts(ExternalCall.PROVIDER_ELEVENLABS, username, started, chars, 0.0, "error", "no API key", subj)
            return null
        }
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
                logTts(ExternalCall.PROVIDER_ELEVENLABS, username, started, chars, 0.0,
                    "error", "http ${resp.statusCode()}", subj)
                null
            } else {
                logTts(ExternalCall.PROVIDER_ELEVENLABS, username, started, chars,
                    Pricing.elevenlabsTtsCost(chars), "ok", null, subj)
                stripId3(resp.body())
            }
        } catch (e: Exception) {
            log.warn("[TTS] ElevenLabs failed: {}", e.message)
            logTts(ExternalCall.PROVIDER_ELEVENLABS, username, started, chars, 0.0, "error", e.message, subj)
            null
        }
    }

    private fun logTts(
        provider: String,
        username: String,
        started: Instant,
        chars: Long,
        cost: Double,
        status: String,
        errorMessage: String?,
        subject: String
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = provider,
                    action = ExternalCall.ACTION_PODCAST_TTS,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = chars,
                    unitType = ExternalCall.UNIT_CHARACTERS,
                    costUsd = cost,
                    status = status,
                    errorMessage = errorMessage,
                    subject = subject.take(120)
                )
            )
        } catch (e: Exception) {
            log.warn("[TTS] could not log external_call: {}", e.message)
        }
    }

    private fun stripId3(bytes: ByteArray): ByteArray {
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
