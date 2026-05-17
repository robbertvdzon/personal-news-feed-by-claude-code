package com.vdzon.newsfeedbackend.podcastfeed.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.external_call.Pricing
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * OpenAI Whisper STT (model `whisper-1`) via `/v1/audio/transcriptions`.
 * Multipart-upload van de MP3 + `response_format=verbose_json` zodat we
 * naast `text` ook de werkelijke audio-`duration` terugkrijgen — die
 * gebruiken we voor zowel de kosten-log als de duur op de feed-card.
 *
 * De Whisper-API accepteert tot 25 MB per upload. Grotere files levert
 * deze client als null terug — de pipeline zet de aflevering dan op
 * FAILED met een leesbare error_message.
 */
@Component
class WhisperClient(
    @Value("\${app.openai.api-key:}") private val openaiKey: String,
    @Value("\${app.openai.base-url:https://api.openai.com}") private val openaiBaseUrl: String,
    private val mapper: ObjectMapper,
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    data class WhisperResult(val text: String, val durationSeconds: Int)

    /**
     * Returnt het transcript + de werkelijke audio-duur, of null bij
     * een fout (ontbrekende key, file te groot, HTTP-fout). Logt elke
     * call als `podcast_transcribe` in external_calls.
     */
    fun transcribe(username: String, subject: String, audio: ByteArray, filename: String = "audio.mp3"): WhisperResult? {
        val started = Instant.now()
        if (openaiKey.isBlank()) {
            logCall(username, started, 0, 0.0, "error", "no OpenAI API key", subject)
            return null
        }
        if (audio.size > MAX_AUDIO_BYTES) {
            val msg = "audio te groot (${audio.size / 1024 / 1024} MB > 25 MB Whisper-limiet)"
            log.warn("[Whisper] {} subject={}", msg, subject)
            logCall(username, started, 0, 0.0, "error", msg, subject)
            return null
        }
        val boundary = "----PNFWhisper${UUID.randomUUID()}"
        val body = buildMultipartBody(boundary, audio, filename)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$openaiBaseUrl/v1/audio/transcriptions"))
            .header("Authorization", "Bearer $openaiKey")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() >= 400) {
                log.warn("[Whisper] {} -> {} body={}", subject, resp.statusCode(), resp.body().take(300))
                logCall(username, started, 0, 0.0, "error", "http ${resp.statusCode()}: ${resp.body().take(120)}", subject)
                null
            } else {
                val tree = mapper.readTree(resp.body())
                val text = tree.path("text").asText("")
                val duration = tree.path("duration").asDouble(0.0).toInt()
                val cost = Pricing.openaiWhisperCost(duration.toLong())
                logCall(username, started, duration.toLong(), cost, "ok", null, subject)
                log.info("[Whisper] ok subject={} duration={}s text-len={} cost=${"%.4f".format(cost)}", subject, duration, text.length)
                WhisperResult(text = text, durationSeconds = duration)
            }
        } catch (e: Exception) {
            log.warn("[Whisper] failed subject={}: {}", subject, e.message)
            logCall(username, started, 0, 0.0, "error", e.message, subject)
            null
        }
    }

    private fun buildMultipartBody(boundary: String, audio: ByteArray, filename: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(s: String) {
            out.write(s.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        // model
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"model\"")
        line("")
        line("whisper-1")
        // response_format
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"response_format\"")
        line("")
        line("verbose_json")
        // file
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"")
        line("Content-Type: audio/mpeg")
        line("")
        out.write(audio)
        line("")
        line("--$boundary--")
        return out.toByteArray()
    }

    private fun logCall(
        username: String, started: Instant,
        durationSeconds: Long, cost: Double,
        status: String, errorMessage: String?, subject: String
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_OPENAI,
                    action = ExternalCall.ACTION_PODCAST_TRANSCRIBE,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = durationSeconds,
                    unitType = ExternalCall.UNIT_SECONDS,
                    costUsd = cost,
                    status = status,
                    errorMessage = errorMessage,
                    subject = subject.take(120)
                )
            )
        } catch (e: Exception) {
            log.warn("[Whisper] could not log external_call: {}", e.message)
        }
    }

    companion object {
        // Whisper-API limiet (officieel 25 MB inclusief upload-overhead).
        // We laten een veiligheidsmarge.
        const val MAX_AUDIO_BYTES = 24L * 1024L * 1024L
    }
}
