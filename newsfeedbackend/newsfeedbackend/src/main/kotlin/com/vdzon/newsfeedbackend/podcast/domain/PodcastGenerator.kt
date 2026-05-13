package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.TtsProvider
import com.vdzon.newsfeedbackend.podcast.infrastructure.PodcastRepository
import com.vdzon.newsfeedbackend.podcast.infrastructure.TtsClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Runs the actual podcast pipeline (Claude script + TTS audio).
 *
 * Lives in its own bean on purpose: PodcastServiceImpl.create() calls
 * generate() through this injected reference, which means Spring's
 * @Async proxy intercepts and dispatches to a background thread.
 * Annotating @Async on a method of PodcastServiceImpl itself would not
 * work because internal `this.method()` calls bypass the proxy.
 */
@Component
class PodcastGenerator(
    private val repo: PodcastRepository,
    private val anthropic: AnthropicClient,
    private val tts: TtsClient,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    fun generate(username: String, id: String) {
        MDC.put("username", username)
        val started = Instant.now()
        try {
            log.info("[Podcast] start generation id={} for user '{}'", id, username)
            update(username, id) { it.copy(status = PodcastStatus.DETERMINING_TOPICS) }

            val current = repo.load(username).find { it.id == id } ?: return
            val targetWords = current.durationMinutes * 140

            update(username, id) { it.copy(status = PodcastStatus.GENERATING_SCRIPT) }
            val scriptResp = anthropic.complete(
                operation = "generatePodcastScript",
                action = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_PODCAST_SCRIPT,
                username = username,
                subject = "Podcast id=$id",
                system = "Je schrijft een Nederlandstalig interview-podcastscript met INTERVIEWER en GAST regels. Zorg voor een vloeiende dialoog van ongeveer $targetWords woorden.",
                user = if (current.customTopics.isNotEmpty())
                    "Onderwerpen: ${current.customTopics.joinToString(", ")}\n\nFormat:\nINTERVIEWER: ...\nGAST: ..."
                else
                    "Stel zelf 5-8 boeiende technologie-onderwerpen samen uit recente nieuwsartikelen. Format:\nINTERVIEWER: ...\nGAST: ..."
            )
            val script = scriptResp.text.ifBlank {
                "INTERVIEWER: Welkom bij DevTalk!\nGAST: Hallo, leuk om hier te zijn."
            }

            val topicsResp = anthropic.complete(
                operation = "extractPodcastTopics",
                action = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_PODCAST_TOPICS,
                username = username,
                subject = "Podcast id=$id",
                system = "Extraheer 5 tot 10 korte onderwerpen uit het script in het Nederlands. Antwoord met een JSON-array van strings.",
                user = script
            )
            val topics = parseStringArray(topicsResp.text).take(10)
            val first = topics.firstOrNull().orEmpty()
            val second = topics.getOrNull(1).orEmpty()
            val title = "DevTalk ${current.podcastNumber}, ${LocalDate.now()} — ${
                listOfNotNull(
                    first.takeIf { it.isNotBlank() },
                    second.takeIf { it.isNotBlank() }
                ).joinToString(", ")
            }"

            update(username, id) {
                it.copy(
                    status = PodcastStatus.GENERATING_AUDIO,
                    scriptText = script,
                    topics = topics,
                    title = title
                )
            }

            val audio = renderAudio(username, id, script, current.ttsProvider)
            // Als renderAudio() null teruggeeft staat er géén mp3 op disk
            // (alle TTS-calls faalden, of het script bevatte geen
            // INTERVIEWER/GAST-regels). De podcast eindigde dan eerder
            // tóch op DONE met audioPath=null, waarna de frontend
            // /audio aanriep en een 404 'mp3-bestand niet gevonden' kreeg.
            // Markeer in dat geval expliciet FAILED zodat de UI niet
            // probeert af te spelen.
            val finalStatus = if (audio != null) PodcastStatus.DONE else PodcastStatus.FAILED
            if (audio == null) {
                log.warn("[Podcast] no audio produced id={} user='{}' — marking FAILED", id, username)
            }
            update(username, id) {
                it.copy(
                    status = finalStatus,
                    audioPath = audio?.toAbsolutePath()?.toString(),
                    durationSeconds = current.durationMinutes * 60,
                    generationSeconds = Duration.between(started, Instant.now()).seconds.toInt()
                )
            }
            meters.counter("newsfeed.podcast.generated", "ttsProvider", current.ttsProvider.name, "status", finalStatus.name).increment()
            meters.timer("newsfeed.podcast.duration").record(Duration.between(started, Instant.now()))
            log.info("[Podcast] generation done id={} status={} title={}", id, finalStatus, title)
        } catch (e: Exception) {
            log.error("[Podcast] generation failed id={}: {}", id, e.message, e)
            update(username, id) { it.copy(status = PodcastStatus.FAILED) }
            meters.counter("newsfeed.podcast.generated", "ttsProvider", "?", "status", "FAILED").increment()
        } finally {
            MDC.clear()
        }
    }

    private fun renderAudio(username: String, id: String, script: String, provider: TtsProvider): Path? {
        val out = ByteArrayOutputStream()
        for (line in script.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val role = when {
                trimmed.startsWith("INTERVIEWER:", ignoreCase = true) -> TtsClient.SpeakerRole.INTERVIEWER
                trimmed.startsWith("GAST:", ignoreCase = true) -> TtsClient.SpeakerRole.GUEST
                else -> continue
            }
            val text = trimmed.substringAfter(":").trim()
            if (text.isEmpty()) continue
            val bytes = tts.generate(username, id, provider, role, text) ?: continue
            out.writeBytes(bytes)
        }
        if (out.size() == 0) return null
        val path = repo.audioPath(username, id)
        Files.createDirectories(path.parent)
        Files.write(path, out.toByteArray())
        // Pad + size in de log zodat we bij een 404-'mp3-bestand niet
        // gevonden' achteraf kunnen verifiëren of het bestand ooit op
        // disk stond (bv. om een verloren PVC-mount uit te sluiten).
        log.info("[Podcast] audio written id={} path={} bytes={}", id, path.toAbsolutePath(), out.size())
        return path
    }

    private fun parseStringArray(text: String): List<String> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val inner = text.substring(start + 1, end)
        return inner.split(",").map { it.trim().trim('"').trim('\'') }.filter { it.isNotBlank() }
    }

    private fun update(username: String, id: String, fn: (Podcast) -> Podcast) {
        val cur = repo.load(username).find { it.id == id } ?: return
        repo.upsert(username, fn(cur))
    }
}
