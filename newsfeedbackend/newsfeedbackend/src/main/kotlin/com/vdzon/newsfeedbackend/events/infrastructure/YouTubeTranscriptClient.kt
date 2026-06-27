package com.vdzon.newsfeedbackend.events.infrastructure

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * KAN-67: probeert het YouTube-transcript van één video op te halen
 * zonder OAuth, via:
 *
 *   1. De publieke `timedtext`-API met `lang=nl` (Nederlandse subs).
 *   2. Hetzelfde endpoint met `lang=en` (Engelse subs).
 *   3. Hetzelfde endpoint met `lang=en&kind=asr` (auto-gegenereerd).
 *
 * Bij elke variant verwachten we een (mogelijk lege) XML-response met
 * `<text>`-tags. De eerste variant die niet-lege tekst oplevert wint.
 * Bij een lege response van alle drie de varianten retourneert deze
 * client `null` — de caller valt dan terug op Whisper (audio-download
 * + transcribe), conform de story.
 *
 * Niet-YouTube URLs leveren altijd `null` op: voor Vimeo, conferentie-
 * portals e.d. is er geen openbare API en valt de flow direct door
 * naar Whisper.
 *
 * Beperkingen: voor nieuwere video's geeft het timedtext-endpoint soms
 * een lege response terug (YouTube verschoof captionTracks naar de
 * InnerTube-API in de page-HTML). Dat is bewust geen escalatie-pad
 * hier — Whisper vangt 't op.
 */
@Component
class YouTubeTranscriptClient(
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    data class Transcript(val text: String, val language: String)

    fun fetch(username: String, videoUrl: String): Transcript? {
        val videoId = extractYouTubeId(videoUrl) ?: run {
            log.debug("[YT-transcript] geen YouTube-ID uit '{}'", videoUrl.take(80))
            return null
        }
        val started = Instant.now()
        var status = "ok"
        var errorMessage: String? = null
        var resultLanguage: String? = null
        var totalBytes = 0L
        return try {
            for (variant in VARIANTS) {
                val url = buildString {
                    append("https://www.youtube.com/api/timedtext?v=").append(videoId)
                    append("&lang=").append(variant.lang)
                    if (variant.asr) append("&kind=asr")
                }
                val (body, ok) = tryFetch(url)
                totalBytes += body.length.toLong()
                if (!ok) continue
                val text = parseTimedTextXml(body)
                if (text.isNotBlank()) {
                    resultLanguage = variant.label
                    log.info("[YT-transcript] success videoId={} via {} ({} chars)",
                        videoId, variant.label, text.length)
                    return Transcript(text = text, language = variant.label)
                }
            }
            log.info("[YT-transcript] geen timedtext-transcript gevonden voor videoId={}", videoId)
            null
        } catch (e: Exception) {
            status = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            log.warn("[YT-transcript] fout voor videoId={}: {}", videoId, errorMessage)
            null
        } finally {
            logCall(username, videoUrl, started, totalBytes, status, errorMessage, resultLanguage)
        }
    }

    /**
     * Trekt de YouTube video-ID uit veelvoorkomende URL-varianten:
     * `youtube.com/watch?v=XXX`, `youtu.be/XXX`, `youtube.com/embed/XXX`,
     * `youtube.com/shorts/XXX`. Returnt null voor niet-YouTube URLs.
     */
    fun extractYouTubeId(videoUrl: String): String? {
        val trimmed = videoUrl.trim()
        if (trimmed.isBlank()) return null
        return try {
            val uri = URI(trimmed)
            val host = uri.host?.lowercase() ?: return null
            val path = uri.path ?: ""
            when {
                host.endsWith("youtu.be") -> path.removePrefix("/").takeIf { it.length in 6..40 }
                host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> {
                    when {
                        path.startsWith("/watch") -> parseQueryParam(uri.rawQuery, "v")
                        path.startsWith("/embed/") -> path.removePrefix("/embed/").takeWhile { it != '/' && it != '?' }
                        path.startsWith("/v/") -> path.removePrefix("/v/").takeWhile { it != '/' && it != '?' }
                        path.startsWith("/shorts/") -> path.removePrefix("/shorts/").takeWhile { it != '/' && it != '?' }
                        else -> null
                    }
                }
                else -> null
            }?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,40}")) }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQueryParam(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        for (part in rawQuery.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq)
            if (k == name) {
                val v = part.substring(eq + 1)
                return URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
        }
        return null
    }

    private fun tryFetch(url: String): Pair<String, Boolean> {
        return try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "PersonalNewsFeed/1.0")
                .timeout(Duration.ofSeconds(20))
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) resp.body() to true else "" to false
        } catch (e: Exception) {
            log.debug("[YT-transcript] fetch faalde voor {}: {}", url.take(120), e.message)
            "" to false
        }
    }

    /**
     * timedtext-XML zit in dit formaat:
     *   <transcript>
     *     <text start="0.0" dur="3.2">Hello everyone</text>
     *     <text start="3.2" dur="2.4">welcome to ...</text>
     *   </transcript>
     *
     * We pakken alle `<text ...>...</text>`-inhoud, decoderen HTML-
     * entities en plakken met spaties. Geen XML-parser nodig (sneller
     * en robuuster voor de paar gevallen waar YouTube zelf het XML
     * niet helemaal correct sluit).
     */
    private fun parseTimedTextXml(xml: String): String {
        if (xml.isBlank()) return ""
        val regex = Regex("<text[^>]*>([\\s\\S]*?)</text>", RegexOption.IGNORE_CASE)
        val sb = StringBuilder()
        for (m in regex.findAll(xml)) {
            val raw = m.groupValues[1]
            val decoded = decodeXmlEntities(raw)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (decoded.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(decoded)
            }
        }
        return sb.toString()
    }

    private fun decodeXmlEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }

    private fun logCall(
        username: String,
        videoUrl: String,
        started: Instant,
        bytes: Long,
        status: String,
        errorMessage: String?,
        language: String?
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_WEB,
                    action = ExternalCall.ACTION_EVENT_VIDEO_TRANSCRIPT_FETCH,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = bytes,
                    unitType = ExternalCall.UNIT_BYTES,
                    costUsd = 0.0,
                    status = if (language != null) status else "error",
                    errorMessage = errorMessage ?: language?.let { null } ?: "no caption track",
                    subject = "video=${videoUrl.take(80)} lang=${language ?: "none"}"
                )
            )
        } catch (e: Exception) {
            log.warn("[YT-transcript] could not log external_call: {}", e.message)
        }
    }

    private data class Variant(val lang: String, val asr: Boolean, val label: String)

    companion object {
        // Volgorde uit de spec: NL → EN → ASR (auto-gegenereerd EN).
        private val VARIANTS = listOf(
            Variant(lang = "nl", asr = false, label = "nl"),
            Variant(lang = "en", asr = false, label = "en"),
            Variant(lang = "en", asr = true, label = "en-asr")
        )
    }
}
