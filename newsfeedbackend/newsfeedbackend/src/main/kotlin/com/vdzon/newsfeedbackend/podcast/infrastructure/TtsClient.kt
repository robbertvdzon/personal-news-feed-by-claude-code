package com.vdzon.newsfeedbackend.podcast.infrastructure

import tools.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiPricingProperties
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

@Component
class TtsClient(
    @param:Value("\${app.openai.api-key:}") private val openaiKey: String,
    @param:Value("\${app.openai.base-url:https://api.openai.com}") private val openaiBaseUrl: String,
    @param:Value("\${app.elevenlabs.api-key:}") private val elevenKey: String,
    @param:Value("\${app.elevenlabs.base-url:https://api.elevenlabs.io}") private val elevenBaseUrl: String,
    @param:Value("\${app.elevenlabs.voice-interviewer:Jn7U4vF8ZkmjZIZRn4Uk}") private val voiceInterviewer: String,
    @param:Value("\${app.elevenlabs.voice-guest:h6uBOiAjLKklte8hdYio}") private val voiceGuest: String,
    @param:Value("\${app.openai.tts-model:tts-1}") private val ttsModel: String,
    private val pricing: AiPricingProperties,
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

    /**
     * KAN-63: single-voice OpenAI TTS-call voor de vertaalde podcast-
     * stream. Anders dan [generate] is dit voor één doorlopende
     * Nederlandse aflevering (geen INTERVIEWER/GAST-wissel) en logt
     * onder een eigen [action]-constant (`podcast_translate_tts`) zodat
     * de kosten apart zichtbaar zijn in het dashboard. Tekst moet ≤ 4096
     * tekens zijn (OpenAI tts-1-limiet); de caller hakt het transcript
     * op en concatenate later met ffmpeg.
     */
    fun generateOpenAiSingleVoice(
        username: String,
        subjectId: String,
        text: String,
        voice: String,
        action: String
    ): ByteArray? = openaiSingleVoice(username, subjectId, text, voice, action)

    enum class SpeakerRole { INTERVIEWER, GUEST }

    private fun openai(username: String, podcastId: String, role: SpeakerRole, text: String): ByteArray? {
        val voice = if (role == SpeakerRole.INTERVIEWER) "onyx" else "alloy"
        return openaiCall(
            username = username,
            subject = "Podcast id=$podcastId role=${role.name}",
            text = text,
            voice = voice,
            speed = 1.2,
            action = ExternalCall.ACTION_PODCAST_TTS
        )
    }

    private fun openaiSingleVoice(
        username: String,
        subjectId: String,
        text: String,
        voice: String,
        action: String
    ): ByteArray? = openaiCall(
        username = username,
        subject = "Podcast id=$subjectId voice=$voice",
        text = text,
        voice = voice,
        // Natuurlijke spreeksnelheid voor de vertaalde NL-podcast (geen
        // sneller-getrokken DevTalk-interview-formaat).
        speed = 1.0,
        action = action
    )

    /**
     * Gedeelde OpenAI TTS-call. Voor het interview-formaat is dit
     * INTERVIEWER/GAST-rol-gestuurd met speed=1.2; voor de KAN-63
     * vertaal-flow is het één vaste stem (nova/shimmer) op speed=1.0.
     * De [action]-constant bepaalt onder welke noemer de call in het
     * kostendashboard verschijnt.
     */
    private fun openaiCall(
        username: String,
        subject: String,
        text: String,
        voice: String,
        speed: Double,
        action: String
    ): ByteArray? {
        val started = Instant.now()
        val chars = text.length.toLong()
        val subj = subject.take(120)
        if (openaiKey.isBlank()) {
            callLogger.logCall(
                ExternalCall.PROVIDER_OPENAI, action, username, started,
                ExternalCall.UNIT_CHARACTERS, "error",
                units = chars, costUsd = 0.0, errorMessage = "no API key", subject = subj
            )
            return null
        }
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to ttsModel,
                "voice" to voice,
                "input" to text,
                "speed" to speed
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
                log.warn("[TTS] OpenAI -> {} action={} voice={}", resp.statusCode(), action, voice)
                callLogger.logCall(
                    ExternalCall.PROVIDER_OPENAI, action, username, started,
                    ExternalCall.UNIT_CHARACTERS, "error",
                    units = chars, costUsd = 0.0,
                    errorMessage = "http ${resp.statusCode()}", subject = subj
                )
                null
            } else {
                callLogger.logCall(
                    ExternalCall.PROVIDER_OPENAI, action, username, started,
                    ExternalCall.UNIT_CHARACTERS, "ok",
                    units = chars, costUsd = pricing.characterCost(ttsModel, chars),
                    errorMessage = null, subject = subj
                )
                resp.body()
            }
        } catch (e: Exception) {
            log.warn("[TTS] OpenAI failed action={}: {}", action, e.message)
            callLogger.logCall(
                ExternalCall.PROVIDER_OPENAI, action, username, started,
                ExternalCall.UNIT_CHARACTERS, "error",
                units = chars, costUsd = 0.0, errorMessage = e.message, subject = subj
            )
            null
        }
    }

    private fun eleven(username: String, podcastId: String, role: SpeakerRole, text: String): ByteArray? {
        val started = Instant.now()
        val chars = text.length.toLong()
        val subj = "Podcast id=$podcastId role=${role.name}".take(120)
        if (elevenKey.isBlank()) {
            callLogger.logCall(
                ExternalCall.PROVIDER_ELEVENLABS, ExternalCall.ACTION_PODCAST_TTS, username, started,
                ExternalCall.UNIT_CHARACTERS, "error",
                units = chars, costUsd = 0.0, errorMessage = "no API key", subject = subj
            )
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
                callLogger.logCall(
                    ExternalCall.PROVIDER_ELEVENLABS, ExternalCall.ACTION_PODCAST_TTS, username, started,
                    ExternalCall.UNIT_CHARACTERS, "error",
                    units = chars, costUsd = 0.0,
                    errorMessage = "http ${resp.statusCode()}", subject = subj
                )
                null
            } else {
                callLogger.logCall(
                    ExternalCall.PROVIDER_ELEVENLABS, ExternalCall.ACTION_PODCAST_TTS, username, started,
                    ExternalCall.UNIT_CHARACTERS, "ok",
                    units = chars, costUsd = Pricing.elevenlabsTtsCost(chars),
                    errorMessage = null, subject = subj
                )
                stripId3(resp.body())
            }
        } catch (e: Exception) {
            log.warn("[TTS] ElevenLabs failed: {}", e.message)
            callLogger.logCall(
                ExternalCall.PROVIDER_ELEVENLABS, ExternalCall.ACTION_PODCAST_TTS, username, started,
                ExternalCall.UNIT_CHARACTERS, "error",
                units = chars, costUsd = 0.0, errorMessage = e.message, subject = subj
            )
            null
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
