package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.ai.AiClient
import com.vdzon.newsfeedbackend.ai.AiRequest
import com.vdzon.newsfeedbackend.ai.SpeechRequest
import com.vdzon.newsfeedbackend.ai.SpeechSegment
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.TtsProvider
import com.vdzon.newsfeedbackend.podcast.infrastructure.PodcastRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Runs the actual podcast pipeline (script + TTS audio), both as Agent
 * Runtime jobs (PNF-3): één agent-job levert script én onderwerpen (de
 * agent mag zelf recent nieuws opzoeken), één TTS-job zet alle
 * sprekerbeurten om naar één MP3.
 *
 * Lives in its own bean on purpose: PodcastServiceImpl.create() calls
 * generate() through this injected reference, which means Spring's
 * @Async proxy intercepts and dispatches to a background thread.
 */
@Component
class PodcastGenerator(
    private val repo: PodcastRepository,
    private val ai: AiClient,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry,
    @param:Value("\${app.elevenlabs.voice-interviewer:Jn7U4vF8ZkmjZIZRn4Uk}") private val elevenVoiceInterviewer: String = "Jn7U4vF8ZkmjZIZRn4Uk",
    @param:Value("\${app.elevenlabs.voice-guest:h6uBOiAjLKklte8hdYio}") private val elevenVoiceGuest: String = "h6uBOiAjLKklte8hdYio"
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Turn(val speaker: String, val text: String)

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
            val response = ai.generate(
                AiRequest(
                    action = ExternalCall.ACTION_PODCAST_SCRIPT,
                    username = username,
                    subject = "Podcast id=$id",
                    resultSchema = mapper.readTree(SCRIPT_SCHEMA),
                    variant = id,
                    instruction = buildString {
                        appendLine("Je schrijft een Nederlandstalig interview-podcastscript (\"DevTalk\") voor twee sprekers: INTERVIEWER en GAST.")
                        appendLine()
                        if (current.customTopics.isNotEmpty()) {
                            appendLine("Onderwerpen: ${current.customTopics.joinToString(", ")}")
                            appendLine("Zoek met je web search tool actuele feiten over deze onderwerpen op zodat het gesprek inhoudelijk klopt.")
                        } else {
                            appendLine("Zoek met je web search tool recent technologienieuws (afgelopen week, vandaag is ${LocalDate.now()}) en kies zelf 5-8 boeiende onderwerpen.")
                        }
                        appendLine()
                        appendLine("Eisen aan het script:")
                        appendLine("- turns: afwisselende sprekersbeurten, beginnend met INTERVIEWER; speaker is exact INTERVIEWER of GAST.")
                        appendLine("- text: alleen gesproken tekst; geen markdown, geen regie-aanwijzingen, geen labels in de tekst.")
                        appendLine("- Doellengte: ongeveer $targetWords woorden in totaal.")
                        appendLine("- topics: 5 tot 10 korte Nederlandse onderwerpen die in het gesprek aan bod komen, belangrijkste eerst.")
                    }
                )
            )
            val turns = response.result?.path("turns")?.values()?.mapNotNull { node ->
                val speaker = node.path("speaker").asString("").uppercase().let { if (it == "GUEST") "GAST" else it }
                val text = node.path("text").asString("").trim()
                if (speaker in setOf("INTERVIEWER", "GAST") && text.isNotBlank()) Turn(speaker, text) else null
            }.orEmpty()
            if (!response.ok || turns.isEmpty()) {
                throw IllegalStateException("Podcastscript genereren mislukt: ${response.errorMessage ?: "geen sprekerbeurten"}")
            }
            val script = turns.joinToString("\n") { "${it.speaker}: ${it.text}" }
            val topics = response.result!!.path("topics").values().mapNotNull { it.asString("").trim().takeIf(String::isNotBlank) }.take(10)
            val title = "DevTalk ${current.podcastNumber}, ${LocalDate.now()} — ${topics.take(2).joinToString(", ")}"

            update(username, id) {
                it.copy(
                    status = PodcastStatus.GENERATING_AUDIO,
                    scriptText = script,
                    topics = topics,
                    title = title
                )
            }

            val audioBytes = renderAudio(username, id, turns, current.ttsProvider)
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

    private fun renderAudio(username: String, id: String, turns: List<Turn>, provider: TtsProvider): ByteArray? {
        val segments = turns.map { turn ->
            val interviewer = turn.speaker == "INTERVIEWER"
            when (provider) {
                TtsProvider.OPENAI -> SpeechSegment(turn.text, if (interviewer) "onyx" else "alloy", 1.2)
                TtsProvider.ELEVENLABS -> SpeechSegment(turn.text, if (interviewer) elevenVoiceInterviewer else elevenVoiceGuest)
            }
        }
        val action = if (provider == TtsProvider.ELEVENLABS) ExternalCall.ACTION_PODCAST_TTS_ELEVENLABS else ExternalCall.ACTION_PODCAST_TTS
        val speech = ai.synthesize(SpeechRequest(action, username, "Podcast id=$id", segments, variant = id))
        if (!speech.ok) {
            log.warn("[Podcast] no audio produced id={} — TTS-job faalde: {}", id, speech.errorMessage)
            return null
        }
        log.info("[Podcast] audio rendered id={} bytes={} turns={}", id, speech.audio!!.size, turns.size)
        return speech.audio
    }

    private fun update(username: String, id: String, fn: (Podcast) -> Podcast) {
        val cur = repo.load(username).find { it.id == id } ?: return
        repo.upsert(username, fn(cur))
    }

    companion object {
        private val SCRIPT_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["topics","turns"],"properties":{
              "topics":{"type":"array","items":{"type":"string"}},
              "turns":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["speaker","text"],"properties":{
                "speaker":{"type":"string","enum":["INTERVIEWER","GAST"]},"text":{"type":"string"}}}}}}
        """.trimIndent()
    }
}
