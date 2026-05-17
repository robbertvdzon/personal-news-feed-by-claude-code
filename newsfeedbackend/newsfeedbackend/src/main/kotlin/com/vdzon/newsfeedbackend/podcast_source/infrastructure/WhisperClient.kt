package com.vdzon.newsfeedbackend.podcast_source.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.external_call.Pricing
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Speech-to-text via de OpenAI Whisper API (model `whisper-1`).
 *
 * KAN-56: vervangt geen bestaande infra — Whisper is een aparte
 * endpoint die multipart/form-data verwacht (audio-bestand + model-
 * naam), niet de JSON-body die de TTS-endpoint gebruikt.
 *
 * Multipart-body wordt gestreamed (PipedInputStream + PipedOutputStream)
 * om heap-pressure bij grote afleveringen (80+ MB) te vermijden.
 */
@Component
class WhisperClient(
    @Value("\${app.openai.api-key:}") private val openaiKey: String,
    @Value("\${app.openai.base-url:https://api.openai.com}") private val openaiBaseUrl: String,
    @Value("\${app.openai.whisper-model:whisper-1}") private val whisperModel: String,
    private val mapper: ObjectMapper,
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    data class TranscribeResult(val text: String, val durationSeconds: Long)

    /**
     * Stuurt [audioFile] als MP3 naar Whisper en geeft de getranscribeerde
     * tekst terug. Bij elke fout (geen API-key, HTTP-fout, parse-fout)
     * geeft 'ie `null` — de caller moet dan terugvallen op show-notes
     * als input voor de Claude-samenvatting (zie story aanname).
     *
     * `audioDurationSec` wordt alleen gebruikt voor de cost-log; de
     * pipeline kent de duur uit `<itunes:duration>` of valt terug op 0.
     */
    fun transcribe(
        username: String,
        episodeGuid: String,
        audioFile: File,
        audioFilename: String,
        audioDurationSec: Long
    ): TranscribeResult? {
        val started = Instant.now()
        val subject = "Podcast episode guid=${episodeGuid.take(60)}"
        if (openaiKey.isBlank()) {
            log.warn("[Whisper] no OPENAI_API_KEY configured — skipping transcription")
            logCall(username, started, audioDurationSec, 0.0, "error", "no API key", subject)
            return null
        }
        return try {
            val boundary = "----PNFWhisper${UUID.randomUUID().toString().replace("-", "")}"
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$openaiBaseUrl/v1/audio/transcriptions"))
                .header("Authorization", "Bearer $openaiKey")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .timeout(Duration.ofMinutes(5))
                .POST(buildMultipartPublisher(boundary, audioFile, audioFilename))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() >= 400) {
                log.warn("[Whisper] {} -> {} body={}", subject, resp.statusCode(), resp.body().take(400))
                logCall(username, started, audioDurationSec, 0.0,
                    "error", "http ${resp.statusCode()}", subject)
                return null
            }
            val tree = mapper.readTree(resp.body())
            val text = tree.path("text").asText("")
            val cost = Pricing.openaiWhisperCost(audioDurationSec)
            logCall(username, started, audioDurationSec, cost, "ok", null, subject)
            log.info("[Whisper] transcribed guid={} chars={} durationSec={} cost=${'$'}{}",
                episodeGuid, text.length, audioDurationSec, "%.4f".format(cost))
            TranscribeResult(text = text, durationSeconds = audioDurationSec)
        } catch (e: Exception) {
            log.warn("[Whisper] transcribe failed: {}", e.message)
            logCall(username, started, audioDurationSec, 0.0,
                "error", e.message ?: e.javaClass.simpleName, subject)
            null
        }
    }

    private fun buildMultipartPublisher(
        boundary: String,
        audioFile: File,
        audioFilename: String
    ): HttpRequest.BodyPublisher {
        val pipe = PipedInputStream()
        val out = PipedOutputStream(pipe)

        thread(start = true, isDaemon = true) {
            try {
                val crlf = "\r\n".toByteArray()

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".toByteArray())
                out.write(whisperModel.toByteArray())
                out.write(crlf)

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n".toByteArray())
                out.write("json".toByteArray())
                out.write(crlf)

                val safeName = audioFilename.replace(Regex("[\\r\\n\"]"), "_").ifBlank { "audio.mp3" }
                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    ("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n").toByteArray()
                )
                out.write("Content-Type: audio/mpeg\r\n\r\n".toByteArray())

                audioFile.inputStream().use { fis ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (fis.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                    }
                }

                out.write(crlf)
                out.write("--$boundary--\r\n".toByteArray())
                out.flush()
                out.close()
            } catch (e: Exception) {
                log.error("[Whisper] streaming-fout in multipart-builder: {}", e.message)
                try { out.close() } catch (ignored: Exception) {}
            }
        }

        return HttpRequest.BodyPublishers.ofInputStream { pipe }
    }

    private fun logCall(
        username: String,
        started: Instant,
        durationSec: Long,
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
                    provider = ExternalCall.PROVIDER_OPENAI,
                    action = ExternalCall.ACTION_PODCAST_TRANSCRIBE,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = durationSec,
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
}
