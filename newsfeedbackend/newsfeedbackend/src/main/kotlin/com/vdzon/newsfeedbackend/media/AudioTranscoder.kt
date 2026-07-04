package com.vdzon.newsfeedbackend.media

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Comprimeert podcast-audio naar mono 32 kbps MP3 zodat Whisper's
 * 25 MB hard limit nooit wordt geraakt. Whisper accepteert wav, mp3,
 * m4a, opus, ogg, flac, webm — mp3 32 kbps mono is voor speech ruim
 * voldoende (Whisper's eigen aanbeveling) en levert ~14 KB/sec, dus
 * ~3.5 uur audio onder de 25 MB.
 *
 * Aanleiding (KAN-60-follow-up): ThoughtWorks-afleveringen (~30 min
 * @ 128 kbps stereo) zijn ~26 MB, net over de limiet. Whisper retourneerde
 * HTTP 413 voor alle 7, ze eindigden als SHOW_NOTES_DONE. Deze transcoder
 * comprimeert pre-Whisper zodat de transcript-fase wel slaagt.
 *
 * ffmpeg moet in PATH staan (zie backend Dockerfile — apt installs ffmpeg
 * in de runtime stage).
 */
@Component
class AudioTranscoder {

    private val log = LoggerFactory.getLogger(javaClass)

    data class TranscodeResult(
        /** Het bestand dat naar Whisper moet. */
        val file: File,
        /** True als [file] een nieuw temp-bestand is dat na gebruik moet
         *  worden gedelete. False als het 't originele invoer-bestand is. */
        val isTemporary: Boolean
    )

    /**
     * Returnt [input] als-is wanneer de file ≤ [maxBytes] is. Anders
     * comprimeert 'ie via ffmpeg naar mono 32 kbps MP3 en returnt een
     * nieuw temp-bestand. Bij ffmpeg-fout (binary mist, exit !=0,
     * timeout) wordt 't originele bestand teruggegeven — Whisper geeft
     * dan z'n eigen 413, en de bestaande SHOW_NOTES_DONE-fallback-pad
     * vangt 't af (geen retry-storm).
     */
    fun ensureBelowSize(input: File, maxBytes: Long): TranscodeResult {
        val size = input.length()
        if (size <= maxBytes) {
            log.debug("[Transcode] {} bytes ≤ {} — geen transcode nodig", size, maxBytes)
            return TranscodeResult(input, isTemporary = false)
        }
        val output = File.createTempFile("podcast-transcoded-", ".mp3")
        log.info("[Transcode] downsampling {} ({} bytes) → mono 32 kbps MP3",
            input.name, size)
        return try {
            val proc = ProcessBuilder(
                "ffmpeg",
                "-y",                            // overwrite output
                "-i", input.absolutePath,
                "-vn",                           // geen video-stream
                "-ac", "1",                      // mono
                "-b:a", "32k",                   // 32 kbps
                "-loglevel", "error",
                output.absolutePath
            ).redirectErrorStream(true).start()

            val finished = proc.waitFor(5, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                output.delete()
                log.warn("[Transcode] ffmpeg timeout (5 min) — fallback naar origineel")
                return TranscodeResult(input, isTemporary = false)
            }
            val exit = proc.exitValue()
            if (exit != 0) {
                val err = proc.inputStream.bufferedReader().readText().take(500)
                output.delete()
                log.warn("[Transcode] ffmpeg exit={} — fallback naar origineel. stderr: {}",
                    exit, err)
                return TranscodeResult(input, isTemporary = false)
            }
            val outSize = output.length()
            if (outSize == 0L) {
                output.delete()
                log.warn("[Transcode] ffmpeg leverde 0-byte output — fallback naar origineel")
                return TranscodeResult(input, isTemporary = false)
            }
            log.info("[Transcode] result {} bytes ({}× kleiner)",
                outSize, (size / outSize.coerceAtLeast(1)))
            TranscodeResult(output, isTemporary = true)
        } catch (e: Exception) {
            log.warn("[Transcode] failed: {} — fallback naar origineel", e.message)
            try { output.delete() } catch (_: Exception) {}
            TranscodeResult(input, isTemporary = false)
        }
    }
}
