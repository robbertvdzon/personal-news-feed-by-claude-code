package com.vdzon.newsfeedbackend.events.infrastructure

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * KAN-67: download de audio-track van een video-URL (YouTube, Vimeo of
 * conferentieportal) naar een tijdelijke MP3, zodat de bestaande
 * [com.vdzon.newsfeedbackend.podcast_source.infrastructure.WhisperClient]
 * 'm naar Whisper kan sturen voor transcriptie.
 *
 * Onder de motorkap gebruikt deze downloader het `yt-dlp`-binary
 * (geïnstalleerd in de runtime-Dockerfile). yt-dlp is de de-facto
 * standaard voor audio-extractie en ondersteunt vrijwel elke video-
 * site; ook conferentieportals die zelf-gehoste players gebruiken.
 *
 * Foutgedrag (afgestemd op het Whisper-foutbeleid):
 *  - yt-dlp ontbreekt of exit != 0   → null + error-log; caller toont
 *    de UI-foutmelding "Samenvatting kon niet worden gemaakt" en de
 *    knop blijft staan.
 *  - Timeout (>10 min)               → idem.
 *  - Lege output                     → idem.
 *
 * De caller is verantwoordelijk voor het opruimen van de teruggegeven
 * temp-file (delete in finally) — consistent met
 * [com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastAudioDownloader].
 */
@Component
class VideoAudioDownloader(
    private val callLogger: ExternalCallLogger,
    @Value("\${app.events.video.ytdlp-binary:yt-dlp}") private val ytdlpBinary: String,
    @Value("\${app.events.video.audio-download-timeout-min:10}") private val timeoutMinutes: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun download(username: String, videoUrl: String): File? {
        val started = Instant.now()
        var status = "ok"
        var errorMessage: String? = null
        var size = 0L
        // yt-dlp wil een output-template zonder extension geven, anders
        // dwingt 't soms een ander format af. We bouwen een prefix en
        // laten yt-dlp er .mp3 achter zetten via --audio-format mp3.
        val tempPrefix = File.createTempFile("event-video-audio-", "")
        // Maak van de placeholder een directory-veilige basis: yt-dlp
        // schrijft naar exact "{prefix}.mp3".
        tempPrefix.delete()
        val outputBase = tempPrefix.absolutePath
        val expectedOutput = File("$outputBase.mp3")
        return try {
            val proc = ProcessBuilder(
                ytdlpBinary,
                "--no-warnings",
                "--no-playlist",
                "--quiet",
                "--no-progress",
                "--extract-audio",
                "--audio-format", "mp3",
                "--audio-quality", "5",
                "-o", "$outputBase.%(ext)s",
                videoUrl
            ).redirectErrorStream(true).start()

            val finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                status = "error"
                errorMessage = "yt-dlp timeout (${timeoutMinutes}m)"
                log.warn("[VideoAudio] {} — {}", videoUrl.take(120), errorMessage)
                expectedOutput.delete()
                return null
            }
            val exit = proc.exitValue()
            if (exit != 0) {
                val tail = proc.inputStream.bufferedReader().readText().takeLast(500)
                status = "error"
                errorMessage = "yt-dlp exit=$exit"
                log.warn("[VideoAudio] {} — exit={} tail={}", videoUrl.take(120), exit, tail)
                expectedOutput.delete()
                return null
            }
            if (!expectedOutput.exists() || expectedOutput.length() == 0L) {
                status = "error"
                errorMessage = "yt-dlp produced no audio"
                log.warn("[VideoAudio] {} — geen MP3-output ({} bytes)",
                    videoUrl.take(120), expectedOutput.length())
                expectedOutput.delete()
                return null
            }
            size = expectedOutput.length()
            log.info("[VideoAudio] downloaded {} → {} bytes", videoUrl.take(80), size)
            expectedOutput
        } catch (e: Exception) {
            status = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            log.warn("[VideoAudio] download faalde voor {}: {}", videoUrl.take(120), errorMessage)
            expectedOutput.delete()
            null
        } finally {
            logCall(username, videoUrl, started, size, status, errorMessage)
        }
    }

    private fun logCall(
        username: String,
        videoUrl: String,
        started: Instant,
        size: Long,
        status: String,
        errorMessage: String?
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_WEB,
                    action = ExternalCall.ACTION_EVENT_VIDEO_AUDIO_DOWNLOAD,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = size,
                    unitType = ExternalCall.UNIT_BYTES,
                    costUsd = 0.0,
                    status = status,
                    errorMessage = errorMessage,
                    subject = "video=${videoUrl.take(100)}"
                )
            )
        } catch (e: Exception) {
            log.warn("[VideoAudio] kon external_call niet loggen: {}", e.message)
        }
    }
}
