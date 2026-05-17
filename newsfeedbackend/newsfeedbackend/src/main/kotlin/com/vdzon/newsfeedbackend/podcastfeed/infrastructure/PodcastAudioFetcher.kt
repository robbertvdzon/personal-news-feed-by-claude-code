package com.vdzon.newsfeedbackend.podcastfeed.infrastructure

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Downloadt de MP3 van een podcast-aflevering naar bytes. Logt elke
 * call als `podcast_audio_fetch` (gratis, alleen voor diagnostics).
 *
 * Bewust apart van [WhisperClient]: de bytes leven kort in geheugen,
 * worden direct doorgesluisd naar Whisper en daarna weggegooid. De
 * volledige MP3 wordt nooit naar de DB geschreven (in tegenstelling
 * tot uitgaande podcasts in KAN-50).
 */
@Component
class PodcastAudioFetcher(
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    /** Returnt MP3-bytes of null bij een fout. */
    fun fetch(username: String, audioUrl: String): ByteArray? {
        val started = Instant.now()
        var status = "ok"
        var errorMessage: String? = null
        var bytes = 0L
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(audioUrl))
                .header("User-Agent", "PersonalNewsFeed/1.0")
                .timeout(Duration.ofMinutes(2))
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() >= 400) {
                status = "error"
                errorMessage = "http ${resp.statusCode()}"
                log.warn("[PodcastAudio] {} -> {}", audioUrl, resp.statusCode())
                return null
            }
            bytes = resp.body().size.toLong()
            return resp.body()
        } catch (e: Exception) {
            status = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            log.warn("[PodcastAudio] {} failed: {}", audioUrl, e.message)
            return null
        } finally {
            logFetch(username, audioUrl, started, bytes, status, errorMessage)
        }
    }

    private fun logFetch(
        username: String, audioUrl: String, started: Instant,
        bytes: Long, status: String, errorMessage: String?
    ) {
        val end = Instant.now()
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = ExternalCall.PROVIDER_WEB,
                    action = ExternalCall.ACTION_PODCAST_AUDIO_FETCH,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    units = bytes,
                    unitType = ExternalCall.UNIT_BYTES,
                    costUsd = 0.0,
                    status = status,
                    errorMessage = errorMessage,
                    subject = audioUrl.take(120)
                )
            )
        } catch (e: Exception) {
            log.warn("[PodcastAudio] could not log external_call: {}", e.message)
        }
    }
}
