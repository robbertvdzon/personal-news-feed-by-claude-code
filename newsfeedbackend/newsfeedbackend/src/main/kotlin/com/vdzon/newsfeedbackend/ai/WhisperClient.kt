package com.vdzon.newsfeedbackend.ai

import tools.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.AiPricingProperties
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
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
 * KAN-60: de transcribe-call retourneert nu een sealed [TranscribeOutcome]
 * zodat de async transcriptfase rate-limit-/server-fouten (429/5xx)
 * kan onderscheiden van fatale fouten. Bij 429/5xx wordt de aflevering
 * geretryd met backoff; bij fatale fouten of "no api key" gaat 'ie naar
 * de show-notes-only-eindstaat (geen oneindige retry-storm).
 */
@Component
class WhisperClient(
    @param:Value("\${app.openai.api-key:}") private val openaiKey: String,
    @param:Value("\${app.openai.base-url:https://api.openai.com}") private val openaiBaseUrl: String,
    @param:Value("\${app.openai.whisper-model:whisper-1}") private val whisperModelFallback: String,
    private val aiModels: AiModelProperties,
    private val pricing: AiPricingProperties,
    private val mapper: ObjectMapper,
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * SF-115: het transcriptiemodel komt uit de actie->model-config
     * (`app.ai.models.podcast_transcribe`, default `gpt-4o-mini-transcribe`).
     * Valt terug op de oude `app.openai.whisper-model` als de mapping ontbreekt.
     */
    private val whisperModel: String
        get() = aiModels.modelFor(ExternalCall.ACTION_PODCAST_TRANSCRIBE) ?: whisperModelFallback
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    sealed class TranscribeOutcome {
        data class Success(val text: String, val durationSeconds: Long) : TranscribeOutcome()
        /** OPENAI_API_KEY ontbreekt — fatale, niet-tijdelijke fout. */
        object NoApiKey : TranscribeOutcome()
        /** HTTP 429 of 5xx van OpenAI; episode moet met backoff geretryd worden. */
        data class RateLimited(val statusCode: Int, val message: String) : TranscribeOutcome()
        /** Andere fout (4xx, netwerkfout, parse-fout) — niet zinvol om te retryen. */
        data class FatalError(val message: String) : TranscribeOutcome()
    }

    /**
     * Stuurt [audioFile] als MP3 naar Whisper. Resultaat is een
     * [TranscribeOutcome] zodat de caller (PodcastTranscriptPipeline) weet
     * of 'ie moet retryen (RateLimited), opgeven (FatalError/NoApiKey)
     * of door kan met de tekst (Success).
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
    ): TranscribeOutcome {
        val started = Instant.now()
        val subject = "Podcast episode guid=${episodeGuid.take(60)}"
        if (openaiKey.isBlank()) {
            log.warn("[Whisper] no OPENAI_API_KEY configured — skipping transcription")
            callLogger.logCall(
                ExternalCall.PROVIDER_OPENAI, ExternalCall.ACTION_PODCAST_TRANSCRIBE, username, started,
                ExternalCall.UNIT_SECONDS, "error",
                units = audioDurationSec, costUsd = 0.0,
                errorMessage = "no API key", subject = subject.take(120)
            )
            return TranscribeOutcome.NoApiKey
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
            val code = resp.statusCode()
            if (code >= 400) {
                val bodyHead = resp.body().take(400)
                log.warn("[Whisper] {} -> {} body={}", subject, code, bodyHead)
                callLogger.logCall(
                    ExternalCall.PROVIDER_OPENAI, ExternalCall.ACTION_PODCAST_TRANSCRIBE, username, started,
                    ExternalCall.UNIT_SECONDS, "error",
                    units = audioDurationSec, costUsd = 0.0,
                    errorMessage = "http $code", subject = subject.take(120)
                )
                // 429 (rate limit) of 5xx (transient server-fout) → retry-pad.
                // 4xx anders dan 429 (bv. 400/413 te grote file) → fataal.
                return if (code == 429 || code in 500..599) {
                    TranscribeOutcome.RateLimited(statusCode = code, message = "HTTP $code: ${bodyHead.take(120)}")
                } else {
                    TranscribeOutcome.FatalError("HTTP $code: ${bodyHead.take(120)}")
                }
            }
            val tree = mapper.readTree(resp.body())
            val text = tree.path("text").asString("")
            val cost = pricing.transcriptionCost(whisperModel, audioDurationSec)
            callLogger.logCall(
                ExternalCall.PROVIDER_OPENAI, ExternalCall.ACTION_PODCAST_TRANSCRIBE, username, started,
                ExternalCall.UNIT_SECONDS, "ok",
                units = audioDurationSec, costUsd = cost,
                errorMessage = null, subject = subject.take(120)
            )
            log.info("[Whisper] transcribed guid={} chars={} durationSec={} cost=${'$'}{}",
                episodeGuid, text.length, audioDurationSec, "%.4f".format(cost))
            TranscribeOutcome.Success(text = text, durationSeconds = audioDurationSec)
        } catch (e: Exception) {
            log.warn("[Whisper] transcribe failed: {}", e.message)
            callLogger.logCall(
                ExternalCall.PROVIDER_OPENAI, ExternalCall.ACTION_PODCAST_TRANSCRIBE, username, started,
                ExternalCall.UNIT_SECONDS, "error",
                units = audioDurationSec, costUsd = 0.0,
                errorMessage = e.message ?: e.javaClass.simpleName, subject = subject.take(120)
            )
            TranscribeOutcome.FatalError(e.message ?: e.javaClass.simpleName)
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
}
