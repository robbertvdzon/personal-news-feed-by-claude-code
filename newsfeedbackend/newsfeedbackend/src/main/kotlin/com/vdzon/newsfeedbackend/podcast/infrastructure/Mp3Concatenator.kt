package com.vdzon.newsfeedbackend.podcast.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * KAN-63: voegt meerdere MP3-snippets samen tot één doorlopende MP3 via
 * ffmpeg's concat-demuxer. Wordt door [com.vdzon.newsfeedbackend.podcast.domain.PodcastTranslator]
 * gebruikt om de chunks die OpenAI's tts-1 (4096-char-limit) teruggeeft
 * tot één aflevering te plakken. ffmpeg is sinds KAN-60-followup
 * beschikbaar in de backend-pod.
 *
 * Implementatie-keuze: concat-demuxer met `-c copy` i.p.v. naive byte-
 * concatenatie of het ffmpeg `concat:`-protocol. Het demuxer-pad is het
 * enige dat in de praktijk werkt voor MP3-snippets van verschillende
 * frame-grenzen — naive byte-append produceert vaak korrupte streams
 * waarbij sommige players (just_audio in Flutter) struikelen op de
 * frame-boundary.
 */
@Component
class Mp3Concatenator {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Concateneert [chunks] tot één MP3. Bij ≤1 chunk: trivial return
     * (geen ffmpeg-call). Bij ≥2 chunks: tempdir + concat-demuxer.
     * Returnt `null` als ffmpeg faalt of niet beschikbaar is — de caller
     * markeert de podcast dan als FAILED.
     */
    fun concat(chunks: List<ByteArray>): ByteArray? {
        if (chunks.isEmpty()) return null
        if (chunks.size == 1) return chunks[0]
        val tmpDir = Files.createTempDirectory("podcast-translate-").toFile()
        return try {
            val listFile = tmpDir.resolve("concat.txt")
            val chunkFiles = chunks.mapIndexed { idx, bytes ->
                val f = tmpDir.resolve("chunk-${"%04d".format(idx)}.mp3")
                f.writeBytes(bytes)
                f
            }
            // ffmpeg concat-demuxer-lijst: `file '<abs-path>'` per regel.
            // Absolute paden + `-safe 0` zodat ffmpeg de paden niet weigert
            // omdat ze leestekens bevatten.
            listFile.writeText(chunkFiles.joinToString("\n") { "file '${it.absolutePath}'" } + "\n")

            val outFile = tmpDir.resolve("out.mp3")
            val pb = ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.absolutePath,
                "-c", "copy",
                outFile.absolutePath
            ).redirectErrorStream(true)
            val proc = pb.start()
            val stdout = ByteArrayOutputStream()
            proc.inputStream.use { it.copyTo(stdout) }
            val finished = proc.waitFor(2, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                log.warn("[Mp3Concat] ffmpeg timed out na 2 minuten — {} chunks", chunks.size)
                return null
            }
            if (proc.exitValue() != 0) {
                log.warn("[Mp3Concat] ffmpeg exit={} chunks={} stderr={}",
                    proc.exitValue(), chunks.size, stdout.toString(Charsets.UTF_8).take(400))
                return null
            }
            if (!outFile.exists() || outFile.length() == 0L) {
                log.warn("[Mp3Concat] ffmpeg produceerde een leeg of ontbrekend out-bestand")
                return null
            }
            log.info("[Mp3Concat] {} MP3-chunks samengevoegd tot {} bytes",
                chunks.size, outFile.length())
            outFile.readBytes()
        } catch (e: Exception) {
            log.warn("[Mp3Concat] failed: {}", e.message, e)
            null
        } finally {
            try {
                tmpDir.walkBottomUp().forEach { it.delete() }
            } catch (e: Exception) {
                log.debug("[Mp3Concat] tempdir cleanup faalde: {}", e.message)
            }
        }
    }
}
