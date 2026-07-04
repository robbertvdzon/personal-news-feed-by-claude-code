package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.external_call.ExternalCall
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
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
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
            val scriptResp = openAi.complete(
                model = aiModels.modelOrDefault(ExternalCall.ACTION_PODCAST_SCRIPT),
                action = ExternalCall.ACTION_PODCAST_SCRIPT,
                username = username,
                subject = "Podcast id=$id",
                system = """Je schrijft een Nederlandstalig interview-podcastscript voor twee sprekers.

STRIKTE FORMAT-EISEN (zonder uitzondering):
- Elke dialoog-regel begint met EXACT "INTERVIEWER:" of "GAST:" (hoofdletters, dubbelpunt, daarna een spatie).
- Gebruik geen andere labels (geen "Host", "Spreker 1", "Presentator", etc.).
- Gebruik geen markdown: geen sterretjes (**), geen #-koppen, geen lijst-streepjes.
- Geen regie-aanwijzingen tussen haakjes of in vierkante haken.
- Geen titel-regels of lege regels tussen sprekers.
- Wissel sprekers af; begin met INTERVIEWER.

Doellengte: ongeveer $targetWords woorden in totaal.""",
                user = buildString {
                    if (current.customTopics.isNotEmpty()) {
                        appendLine("Onderwerpen: ${current.customTopics.joinToString(", ")}")
                    } else {
                        appendLine("Stel zelf 5-8 boeiende technologie-onderwerpen samen uit recente nieuwsartikelen.")
                    }
                    appendLine()
                    appendLine("Voorbeeld van het verplichte format (volg dit EXACT, geen markdown):")
                    appendLine("INTERVIEWER: Welkom bij DevTalk.")
                    appendLine("GAST: Dank je, leuk om hier te zijn.")
                    appendLine("INTERVIEWER: Wat is recent het belangrijkste nieuws?")
                    appendLine("GAST: ...")
                }
            )
            val script = scriptResp.text.ifBlank {
                "INTERVIEWER: Welkom bij DevTalk!\nGAST: Hallo, leuk om hier te zijn."
            }

            val topicsResp = openAi.complete(
                model = aiModels.modelOrDefault(ExternalCall.ACTION_PODCAST_TOPICS),
                action = ExternalCall.ACTION_PODCAST_TOPICS,
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

            val audioBytes = renderAudio(username, id, script, current.ttsProvider)
            // Als renderAudio() null teruggeeft is er géén audio gegenereerd
            // (alle TTS-calls faalden, of het script bevatte geen herkenbare
            // sprekerregels). Markeer in dat geval expliciet FAILED zodat de
            // UI niet probeert af te spelen.
            val finalStatus = if (audioBytes != null) PodcastStatus.DONE else PodcastStatus.FAILED
            if (audioBytes != null) {
                repo.saveAudio(username, id, audioBytes)
            }
            update(username, id) {
                it.copy(
                    status = finalStatus,
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

    private fun renderAudio(username: String, id: String, script: String, provider: TtsProvider): ByteArray? {
        val parsed = PodcastScriptParser.parse(script)
        val out = ByteArrayOutputStream()
        var ttsSuccess = 0
        for (segment in parsed.segments) {
            val bytes = tts.generate(username, id, provider, segment.role, segment.text) ?: continue
            ttsSuccess += 1
            out.writeBytes(bytes)
        }
        if (out.size() == 0) {
            logRenderFailure(id, script, parsed, ttsSuccess)
            return null
        }
        val data = out.toByteArray()
        log.info(
            "[Podcast] audio rendered id={} bytes={} matchedLines={}/{} ttsOk={}",
            id, data.size, parsed.matchedLines, parsed.totalContentLines, ttsSuccess
        )
        return data
    }

    private fun logRenderFailure(
        id: String,
        script: String,
        parsed: PodcastScriptParser.Result,
        ttsSuccess: Int
    ) {
        val snippet = script.take(500).replace('\n', ' ')
        val cause = when {
            parsed.matchedLines == 0 ->
                "script-parser herkende geen INTERVIEWER/GAST-regels (LLM produceerde een afwijkend format)"
            ttsSuccess == 0 ->
                "alle ${parsed.matchedLines} TTS-calls faalden (zie [TTS]-warnings)"
            else ->
                "onbekend (matched=${parsed.matchedLines}, ttsOk=$ttsSuccess maar 0 bytes)"
        }
        log.warn(
            "[Podcast] no audio produced id={} — {}. matchedLines={}/{} ttsOk={} scriptLen={} snippet=\"{}\"",
            id, cause, parsed.matchedLines, parsed.totalContentLines, ttsSuccess, script.length, snippet
        )
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
