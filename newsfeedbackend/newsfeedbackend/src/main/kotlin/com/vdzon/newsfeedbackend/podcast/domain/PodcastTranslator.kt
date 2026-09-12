package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.ai.AiAttachment
import com.vdzon.newsfeedbackend.ai.AiClient
import com.vdzon.newsfeedbackend.ai.AiRequest
import com.vdzon.newsfeedbackend.ai.SpeechRequest
import com.vdzon.newsfeedbackend.ai.SpeechSegment
import tools.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.infrastructure.PodcastRepository
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeLookup
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * KAN-63: vertaalt een RSS-podcast-aflevering (Engels transcript) naar
 * een Nederlandse audio-podcast.
 *
 * Status-flow (zie [PodcastStatus]):
 *   PENDING → TRANSLATING       (Agent Runtime-job, Engels → Nederlands)
 *           → TTS_GENERATING    (Agent Runtime SPEECH_SYNTHESIS-job, één MP3)
 *           → DONE / FAILED
 *
 * Lives als aparte bean (i.p.v. een method op [PodcastServiceImpl]) om
 * dezelfde reden als [PodcastGenerator]: Spring's `@Async`-proxy
 * intercepteert alleen cross-bean calls, niet `this.translate()`. Dus
 * de translate-controller injecteert deze bean en roept 'm aan.
 */
@Component
class PodcastTranslator(
    private val repo: PodcastRepository,
    private val episodeRepo: PodcastEpisodeLookup,
    private val ai: AiClient,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Doel-lengte van de vertaling. ~9000 woorden ≈ 1u audio bij
         * 150 wpm — verankerd in de KAN-63-story.
         */
        const val TARGET_WORDS_MAX = 9000

        /**
         * Vaste OpenAI-stem voor de vertaalde aflevering. Refiner-keuze
         * was "nova" — klinkt natuurlijk in NL. Wijzigen kan door deze
         * constante (of een config-property) aan te passen.
         */
        const val OPENAI_VOICE = "nova"
    }

    @Async
    fun translate(username: String, podcastId: String, episodeGuid: String) {
        MDC.put("username", username)
        val started = Instant.now()
        try {
            log.info("[PodcastTranslate] start id={} episode={} user='{}'", podcastId, episodeGuid, username)
            val episode = episodeRepo.get(username, episodeGuid)
            if (episode == null) {
                fail(username, podcastId, "Bron-aflevering niet gevonden")
                return
            }
            val transcript = episode.transcript.trim()
            if (transcript.isEmpty()) {
                fail(username, podcastId, "Bron-aflevering heeft geen transcript")
                return
            }

            // === Fase 1: vertaling ===
            update(username, podcastId) { it.copy(status = PodcastStatus.TRANSLATING, errorMessage = null) }
            val translation = ai.generate(
                AiRequest(
                    action = ExternalCall.ACTION_PODCAST_TRANSLATE,
                    username = username,
                    subject = "Podcast translate id=$podcastId guid=${episodeGuid.take(40)}",
                    resultSchema = mapper.readTree("""{"type":"object","additionalProperties":false,"required":["text"],"properties":{"text":{"type":"string"}}}"""),
                    attachments = listOf(AiAttachment.text("transcript", transcript, "text/plain")),
                    variant = podcastId,
                    instruction = TRANSLATE_SYSTEM_PROMPT + "\n\nHet Engelse transcript staat in het invoerobject 'transcript' (/job/input/objects/transcript/content). " +
                        "Zet de volledige Nederlandse tekst in het veld 'text'."
                )
            )
            val translatedText = translation.result?.path("text")?.asString("")?.trim().orEmpty()
            if (!translation.ok || translatedText.isBlank()) {
                fail(username, podcastId, "Vertaal-job faalde: ${translation.errorMessage ?: "lege respons"}")
                return
            }
            log.info("[PodcastTranslate] vertaling klaar id={} chars={} job={}", podcastId, translatedText.length, translation.jobId)

            // === Fase 2: TTS via de runtime (chunking + concat gebeuren daar) ===
            update(username, podcastId) {
                it.copy(
                    status = PodcastStatus.TTS_GENERATING,
                    scriptText = translatedText
                )
            }
            val speech = ai.synthesize(
                SpeechRequest(
                    action = ExternalCall.ACTION_PODCAST_TRANSLATE_TTS,
                    username = username,
                    subject = "Podcast id=$podcastId voice=$OPENAI_VOICE",
                    segments = listOf(SpeechSegment(translatedText, OPENAI_VOICE, 1.0)),
                    variant = podcastId
                )
            )
            if (!speech.ok) {
                fail(username, podcastId, "TTS-job faalde: ${speech.errorMessage ?: "geen audio"}")
                return
            }
            val audio = speech.audio!!
            repo.saveAudio(username, podcastId, audio)

            // === Klaar ===
            // Ruwe duur-schatting voor de UI: gemiddeld 150 wpm voor de
            // gegenereerde stem, gebaseerd op de NL-woorden in het script.
            val words = translatedText.split(Regex("\\s+")).count { it.isNotBlank() }
            val estDurationSec = (words * 60 / 150).coerceAtLeast(1)
            update(username, podcastId) {
                it.copy(
                    status = PodcastStatus.DONE,
                    durationSeconds = estDurationSec,
                    generationSeconds = Duration.between(started, Instant.now()).seconds.toInt(),
                    errorMessage = null
                )
            }
            meters.counter("newsfeed.podcast.translated", "status", "DONE").increment()
            meters.timer("newsfeed.podcast.translate.duration").record(Duration.between(started, Instant.now()))
            log.info("[PodcastTranslate] DONE id={} audioBytes={} duration={}s",
                podcastId, audio.size, estDurationSec)
        } catch (e: Exception) {
            log.error("[PodcastTranslate] crash id={}: {}", podcastId, e.message, e)
            fail(username, podcastId, e.message ?: e.javaClass.simpleName)
        } finally {
            MDC.clear()
        }
    }

    private fun fail(username: String, podcastId: String, reason: String) {
        log.warn("[PodcastTranslate] FAILED id={} reason={}", podcastId, reason)
        update(username, podcastId) {
            it.copy(status = PodcastStatus.FAILED, errorMessage = reason.take(400))
        }
        meters.counter("newsfeed.podcast.translated", "status", "FAILED").increment()
    }

    private fun update(username: String, id: String, fn: (Podcast) -> Podcast) {
        val cur = repo.load(username).find { it.id == id } ?: return
        repo.upsert(username, fn(cur))
    }

    private val TRANSLATE_SYSTEM_PROMPT = """
Je bent een tweetalige podcast-vertaler (Engels → Nederlands) voor een Nederlandstalige tech-podcast-app.

OPDRACHT: vertaal het Engelse transcript hieronder naar vloeiend, natuurlijk Nederlands dat klinkt alsof het origineel in het Nederlands is opgenomen.

REGELS:
1. Houd Engelse vaktermen LETTERLIJK (niet vertalen): RLHF, transformer, embeddings, prompt-engineering, MoE, agent, fine-tuning, attention, token, context window, large language model (LLM), retrieval-augmented generation (RAG), vector database, latent space, foundation model, multimodal, inference, benchmark, hallucination, alignment, scaling laws, dataset, pipeline, framework, repository, API, SDK, GPU, runtime, payload, schema. Bij twijfel: laat de Engelse term staan.
2. Productnamen, bedrijfsnamen, modelnamen en code-identifiers blijven onveranderd (OpenAI, Anthropic, GPT-4o, Claude, Gemini, Llama, ChatGPT, Hugging Face, etc.).
3. Verwijder GEEN inhoud, GEEN argumentatielijn, GEEN voorbeelden. Schrijf alles om naar Nederlands, niet samen te vatten.
4. UITZONDERING — lengte-beperking: als de letterlijke vertaling langer zou worden dan ${TARGET_WORDS_MAX} Nederlandse woorden (≈ 1 uur audio), kort dan zelf in met BEHOUD van de hoofdinhoud en de argumentatielijn. Schrap dan eerder herhalingen, sidetracks en filler ("you know", "I mean", "right?") dan de inhoudelijke punten.
5. Verwijder hesitations en pure filler ("uh", "uhm", "yeah yeah yeah", herhaalde halve zinnen) — alleen zinvolle inhoud blijft.
6. Spreker-attributies ("Host:", "Guest:", "Speaker 1:", etc.) NIET overnemen. Output is één doorlopende monoloog die de TTS gewoon kan voorlezen.
7. GEEN markdown, GEEN sterretjes, GEEN koppen, GEEN lijst-streepjes, GEEN regie-aanwijzingen tussen haakjes. Alleen platte Nederlandse zinnen.
8. Output bevat ALLEEN de vertaalde tekst — geen inleiding ("Hier is de vertaling:"), geen meta-commentaar, geen Engelse uitleg achteraf.
    """.trimIndent()
}
