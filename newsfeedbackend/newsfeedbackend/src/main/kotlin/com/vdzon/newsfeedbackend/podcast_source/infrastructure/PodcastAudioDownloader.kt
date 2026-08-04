package com.vdzon.newsfeedbackend.podcast_source.infrastructure

import com.vdzon.newsfeedbackend.common.SsrfUrlValidator
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Downloads een podcast-MP3 naar een temp-file. De file wordt gestreamed
 * naar disk om heap-pressure te vermijden bij grote afleveringen (80+ MB).
 * De caller is verantwoordelijk voor opruimen van de temp-file na verwerking.
 * De cost-log (action=podcast_audio_download) registreert alleen aantal bytes,
 * voor diagnose; bandbreedte is gratis.
 */
@Component
class PodcastAudioDownloader(
    private val callLogger: ExternalCallLogger,
    // Zie ArticleFetcher.ssrfAllowLoopback — zelfde e2e-only escape-hatch, hier voor de
    // audio-/enclosure-URL die uit de podcast-feed komt (tweede-orde, niet door de gebruiker ingetypt).
    @param:Value("\${app.security.ssrf.allow-loopback:false}") private val ssrfAllowLoopback: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    fun download(username: String, episodeGuid: String, audioUrl: String): File? {
        val started = Instant.now()
        var status = "ok"
        var errorMessage: String? = null
        var size: Long = 0L
        val tempFile = File.createTempFile("podcast-", ".mp3")
        try {
            // De audio-URL komt uit de feed-inhoud en is dus nooit gevalideerd bij opslaan;
            // verse DNS-resolutie vlak vóór het request (dekt ook DNS-rebinding af).
            // Deze klasse logt via finally, dus `return null` logt de ExternalCall gewoon.
            val validation = SsrfUrlValidator.validate(audioUrl, allowLoopback = ssrfAllowLoopback)
            if (validation is SsrfUrlValidator.ValidationResult.Invalid) {
                log.warn("[PodcastAudio] blocked SSRF-risky URL {}: {}", audioUrl, validation.reason)
                status = "error"
                errorMessage = "geblokkeerd: ${validation.reason}"
                tempFile.delete()
                return null
            }
            val req = HttpRequest.newBuilder().uri(URI.create(audioUrl))
                .header("User-Agent", "PersonalNewsFeed/1.0")
                .timeout(Duration.ofMinutes(5))
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tempFile.toPath()))
            if (resp.statusCode() >= 400) {
                status = "error"
                errorMessage = "http ${resp.statusCode()}"
                log.warn("[PodcastAudio] {} -> {}", audioUrl, resp.statusCode())
                tempFile.delete()
                return null
            }
            size = tempFile.length()
            return tempFile
        } catch (e: Exception) {
            log.warn("[PodcastAudio] download failed {}: {}", audioUrl, e.message)
            status = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            tempFile.delete()
            return null
        } finally {
            log(username, episodeGuid, audioUrl, started, size, status, errorMessage)
        }
    }

    private fun log(
        username: String,
        episodeGuid: String,
        audioUrl: String,
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
                    action = ExternalCall.ACTION_PODCAST_AUDIO_DOWNLOAD,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = size,
                    unitType = ExternalCall.UNIT_BYTES,
                    costUsd = 0.0,
                    status = status,
                    errorMessage = errorMessage,
                    subject = "guid=${episodeGuid.take(60)} url=${audioUrl.take(60)}"
                )
            )
        } catch (e: Exception) {
            log.warn("[PodcastAudio] could not log external_call: {}", e.message)
        }
    }
}
