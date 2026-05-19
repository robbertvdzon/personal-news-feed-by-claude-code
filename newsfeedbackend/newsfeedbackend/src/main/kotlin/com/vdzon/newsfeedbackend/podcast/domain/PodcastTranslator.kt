package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.infrastructure.Mp3Concatenator
import com.vdzon.newsfeedbackend.podcast.infrastructure.PodcastRepository
import com.vdzon.newsfeedbackend.podcast.infrastructure.TtsClient
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
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
 *   PENDING → TRANSLATING       (gpt-4o-mini, Engels → Nederlands)
 *           → TTS_GENERATING    (tts-1 in chunks, ffmpeg concat)
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
    private val episodeRepo: PodcastEpisodeRepository,
    private val openai: OpenAiChatClient,
    private val tts: TtsClient,
    private val concatenator: Mp3Concatenator,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** OpenAI tts-1 weigert input > 4096 chars. Wij houden marge. */
        const val TTS_CHUNK_LIMIT = 4000

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
            val translation = openai.complete(
                action = ExternalCall.ACTION_PODCAST_TRANSLATE,
                username = username,
                subject = "Podcast translate id=$podcastId guid=${episodeGuid.take(40)}",
                system = TRANSLATE_SYSTEM_PROMPT,
                user = transcript,
                // Bovengrens: ~9000 NL-woorden ≈ ~13k tokens. Met marge
                // voor token-densiteits-verschillen pakken we 16k.
                maxOutputTokens = 16384
            )
            if (translation.status != "ok" || translation.text.isBlank()) {
                fail(username, podcastId, "Vertaal-call faalde: ${translation.errorMessage ?: "lege respons"}")
                return
            }
            val translatedText = translation.text.trim()
            log.info(
                "[PodcastTranslate] vertaling klaar id={} chars={} tokensIn={} tokensOut={} cost=${'$'}{}",
                podcastId, translatedText.length, translation.inputTokens, translation.outputTokens,
                "%.4f".format(translation.costUsd)
            )

            // === Fase 2: TTS-chunks + ffmpeg-concat ===
            update(username, podcastId) {
                it.copy(
                    status = PodcastStatus.TTS_GENERATING,
                    scriptText = translatedText
                )
            }
            val chunks = chunkForTts(translatedText, TTS_CHUNK_LIMIT)
            log.info("[PodcastTranslate] {} TTS-chunks voor id={}", chunks.size, podcastId)
            val mp3Parts = mutableListOf<ByteArray>()
            for ((idx, chunk) in chunks.withIndex()) {
                val bytes = tts.generateOpenAiSingleVoice(
                    username = username,
                    subjectId = podcastId,
                    text = chunk,
                    voice = OPENAI_VOICE,
                    action = ExternalCall.ACTION_PODCAST_TRANSLATE_TTS
                )
                if (bytes == null) {
                    fail(username, podcastId, "TTS-call faalde op chunk ${idx + 1}/${chunks.size}")
                    return
                }
                mp3Parts += bytes
            }
            val audio = concatenator.concat(mp3Parts)
            if (audio == null || audio.isEmpty()) {
                fail(username, podcastId, "ffmpeg-concat van TTS-chunks faalde")
                return
            }
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

    /**
     * Splitst [text] in chunks van ≤[limit] tekens, brekend op
     * zin-einden waar mogelijk. Een zin die zelf langer is dan [limit]
     * wordt op spaties teruggehakt; in het uiterste geval pakt 'ie
     * [limit] tekens hard af.
     */
    internal fun chunkForTts(text: String, limit: Int): List<String> {
        val sentences = splitIntoSentences(text)
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.isEmpty()) continue
            if (s.length > limit) {
                // Sentence too long on its own — flush the buffer first, then
                // split this single sentence on word boundaries.
                if (buf.isNotEmpty()) {
                    chunks += buf.toString().trim()
                    buf.setLength(0)
                }
                chunks += splitLongSentence(s, limit)
                continue
            }
            val candidate = if (buf.isEmpty()) s else buf.toString() + " " + s
            if (candidate.length > limit) {
                chunks += buf.toString().trim()
                buf.setLength(0)
                buf.append(s)
            } else {
                if (buf.isEmpty()) buf.append(s) else { buf.append(' '); buf.append(s) }
            }
        }
        if (buf.isNotEmpty()) chunks += buf.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }

    private fun splitIntoSentences(text: String): List<String> {
        // Eenvoudige zin-splitter: na . ! ? gevolgd door whitespace en
        // een hoofdletter / cijfer / aanhalingsteken. Werkt op
        // alledaagse podcast-tekst; geen volledige NL-tokenizer nodig.
        val regex = Regex("(?<=[.!?])\\s+(?=[\"'(\\[A-Z0-9])")
        return text.split(regex)
    }

    private fun splitLongSentence(sentence: String, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var remaining = sentence
        while (remaining.length > limit) {
            val window = remaining.substring(0, limit)
            // Zoek de laatste spatie binnen het venster — als die bestaat,
            // breek daar zodat we niet midden in een woord splitten.
            val breakAt = window.lastIndexOf(' ').takeIf { it > limit / 2 } ?: limit
            out += remaining.substring(0, breakAt).trim()
            remaining = remaining.substring(breakAt).trim()
        }
        if (remaining.isNotBlank()) out += remaining
        return out
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
