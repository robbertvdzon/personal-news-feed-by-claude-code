package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.events.EventVideo
import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.events.infrastructure.EventVideoRepository
import com.vdzon.newsfeedbackend.events.infrastructure.VideoAudioDownloader
import com.vdzon.newsfeedbackend.events.infrastructure.YouTubeTranscriptClient
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.media.AudioTranscoder
import com.vdzon.newsfeedbackend.ai.WhisperClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * KAN-67: on-demand Nederlandse samenvatting van één event-video.
 *
 * Stappen (synchroon binnen de POST-call, frontend toont laad-indicator):
 *
 *   1. Lookup `event_videos`-rij. Bestaat de samenvatting al → direct
 *      teruggeven (geen AI-call, AC: idempotent).
 *   2. Transcript:
 *        a. YouTube timedtext via [YouTubeTranscriptClient] (nl → en → asr).
 *        b. Bij leeg → audio downloaden via [VideoAudioDownloader]
 *           (yt-dlp) en transcriberen via [WhisperClient] (hergebruik
 *           van de podcast-flow, mét bestaande retry-/skip-logica).
 *   3. Transcript niet beschikbaar → null (caller stuurt 502).
 *   4. Claude maakt uitgebreide Nederlandse samenvatting → opgeslagen
 *      via [EventVideoRepository.setSummary] (laat de wekelijkse
 *      discovery-upsert verder onaangeroerd).
 *   5. Externe calls worden gelogd (`external_calls.jsonl`) en
 *      Micrometer registreert duration + result.
 *
 * Per-video locking voorkomt dat twee gelijktijdige drukken op de knop
 * twee Whisper- en twee Claude-calls triggeren — de tweede caller
 * wacht op de lock en krijgt vervolgens de opgeslagen samenvatting
 * terug (zonder eigen AI-call).
 */
@Component
class EventVideoSummaryPipeline(
    private val events: EventRepository,
    private val videos: EventVideoRepository,
    private val ytTranscript: YouTubeTranscriptClient,
    private val audioDownloader: VideoAudioDownloader,
    private val transcoder: AudioTranscoder,
    private val whisper: WhisperClient,
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun ensureSummary(username: String, eventId: String, videoUrl: String): EventVideo? {
        val key = "$username|$eventId|$videoUrl"
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        MDC.put("username", username)
        val started = Instant.now()
        var resultTag = "ok"
        try {
            val existing = videos.get(username, eventId, videoUrl) ?: run {
                log.warn("[VideoSummary] video bestaat niet voor user='{}' event='{}' url='{}'",
                    username, eventId, videoUrl.take(100))
                resultTag = "not_found"
                return null
            }
            if (!existing.summaryNl.isNullOrBlank()) {
                log.info("[VideoSummary] cache-hit voor event='{}' url='{}' ({} chars)",
                    eventId, videoUrl.take(80), existing.summaryNl.length)
                resultTag = "cache_hit"
                return existing
            }
            val event = events.load(username).find { it.id == eventId }
            val transcript = obtainTranscript(username, videoUrl) ?: run {
                log.warn("[VideoSummary] geen transcript verkrijgbaar voor url='{}' — geen samenvatting",
                    videoUrl.take(100))
                resultTag = "no_transcript"
                return null
            }
            val summary = summarize(username, existing, event?.name, transcript)
            if (summary.isNullOrBlank()) {
                log.warn("[VideoSummary] Claude leverde geen samenvatting voor url='{}'", videoUrl.take(100))
                resultTag = "summarize_failed"
                return null
            }
            videos.setSummary(username, eventId, videoUrl, summary)
            log.info("[VideoSummary] klaar voor event='{}' url='{}': {} chars",
                eventId, videoUrl.take(80), summary.length)
            return existing.copy(summaryNl = summary, updatedAt = Instant.now())
        } catch (e: Exception) {
            log.error("[VideoSummary] onverwachte fout voor url='{}': {}", videoUrl.take(100), e.message, e)
            resultTag = "error"
            return null
        } finally {
            try {
                meters.timer("newsfeed.event_videos.summary.duration",
                    "username", username, "result", resultTag
                ).record(Duration.between(started, Instant.now()))
                meters.counter("newsfeed.event_videos.summary.count",
                    "username", username, "result", resultTag
                ).increment()
            } catch (e: Exception) { log.debug("Metrics-update mislukt (nooit fataal): {}", e.message) }
            MDC.clear()
            lock.unlock()
        }
    }

    /**
     * Probeert eerst YouTube-ondertiteling, valt anders terug op
     * Whisper. Returnt null als beide niets opleveren — caller toont
     * dan een UI-foutmelding (knop blijft staan).
     */
    private fun obtainTranscript(username: String, videoUrl: String): String? {
        ytTranscript.fetch(username, videoUrl)?.let { yt ->
            if (yt.text.isNotBlank()) {
                log.info("[VideoSummary] transcript via YouTube ({}) — {} chars",
                    yt.language, yt.text.length)
                return yt.text
            }
        }
        return whisperTranscript(username, videoUrl)
    }

    private fun whisperTranscript(username: String, videoUrl: String): String? {
        val audio = audioDownloader.download(username, videoUrl) ?: return null
        var transcoded: AudioTranscoder.TranscodeResult? = null
        return try {
            transcoded = transcoder.ensureBelowSize(audio, MAX_WHISPER_BYTES)
            val outcome = whisper.transcribe(
                username = username,
                episodeGuid = "event-video:${videoUrl.take(60)}",
                audioFile = transcoded.file,
                audioFilename = "event-video.mp3",
                audioDurationSec = 0L
            )
            when (outcome) {
                is WhisperClient.TranscribeOutcome.Success -> outcome.text.takeIf { it.isNotBlank() }
                is WhisperClient.TranscribeOutcome.NoApiKey -> {
                    log.warn("[VideoSummary] Whisper heeft geen API-key — geen transcript")
                    null
                }
                is WhisperClient.TranscribeOutcome.RateLimited -> {
                    log.warn("[VideoSummary] Whisper rate-limited ({}) — gebruiker mag later opnieuw proberen",
                        outcome.statusCode)
                    null
                }
                is WhisperClient.TranscribeOutcome.FatalError -> {
                    log.warn("[VideoSummary] Whisper fatale fout: {}", outcome.message)
                    null
                }
            }
        } finally {
            try { transcoded?.takeIf { it.isTemporary }?.file?.delete() } catch (e: Exception) { log.debug("Temp-transcode-file niet opgeruimd: {}", e.message) }
            try { audio.delete() } catch (e: Exception) { log.debug("Audio-file niet opgeruimd: {}", e.message) }
        }
    }

    private fun summarize(
        username: String,
        video: EventVideo,
        eventName: String?,
        transcript: String
    ): String? {
        val sample = if (transcript.length > MAX_CLAUDE_INPUT_CHARS) {
            transcript.take(MAX_CLAUDE_INPUT_CHARS) + "\n[...afgekort wegens lengte...]"
        } else {
            transcript
        }
        val context = buildString {
            if (!eventName.isNullOrBlank()) appendLine("Event: $eventName")
            appendLine("Titel: ${video.title}")
            if (!video.descriptionNl.isNullOrBlank()) {
                appendLine("Korte beschrijving: ${video.descriptionNl}")
            }
            appendLine()
            appendLine("Transcript (mogelijk afgekapt; van YouTube of Whisper):")
            append(sample)
        }
        val ai = openAi.complete(
            model = aiModels.modelOrDefault(ExternalCall.ACTION_EVENT_VIDEO_SUMMARIZE),
            action = ExternalCall.ACTION_EVENT_VIDEO_SUMMARIZE,
            username = username,
            subject = "EventVideo '${video.title.take(80)}'",
            maxOutputTokens = 4096,
            system = """
                Je vat conferentie-/tech-video's samen in het Nederlands.
                Doel: een uitgebreide, inhoudelijke samenvatting waarmee de
                lezer een goed beeld krijgt van de talk zonder de hele video
                te kijken.

                Schrijf 4-7 alinea's plain-text Nederlands (~500-900 woorden).
                Reflecteer concrete inhoud uit het transcript: namen van
                sprekers, tools, frameworks, versies, voorbeelden, citaten,
                cijfers. Volg de structuur van de talk (introductie →
                hoofdpunten → demo's → conclusies/Q&A).

                Vermijd marketing-platitudes en algemeenheden. Bij heel kort
                transcript (<2.000 chars) mag je 2-3 alinea's volstaan — vul
                niets op.

                Géén markdown-headers, géén bullet-list — gewone prose met
                lege regels tussen alinea's. Géén intro à la "Hier volgt
                een samenvatting" — duik direct in de inhoud.

                Antwoord uitsluitend met de samenvatting zelf, geen
                code-fences, geen JSON, geen prose ervoor of erna.
            """.trimIndent(),
            user = context
        )
        return ai.text.trim().takeIf { it.isNotBlank() }
    }

    companion object {
        /** Whisper-limit (zie [com.vdzon.newsfeedbackend.podcast_source.domain.PodcastTranscriptProcessor]). */
        private const val MAX_WHISPER_BYTES = 25L * 1024 * 1024 - 1024 * 1024
        /**
         * Max aantal transcript-chars dat we naar Claude sturen. Gelijk
         * aan de podcast-keynote-grens — ruim binnen Sonnet's 200k window
         * en goed voor ~60-90 min spreektijd.
         */
        private const val MAX_CLAUDE_INPUT_CHARS = 80_000
    }
}
