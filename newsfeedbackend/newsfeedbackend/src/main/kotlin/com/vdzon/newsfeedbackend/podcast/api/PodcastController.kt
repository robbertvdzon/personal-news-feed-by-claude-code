package com.vdzon.newsfeedbackend.podcast.api

import com.vdzon.newsfeedbackend.common.NotFoundException
import com.vdzon.newsfeedbackend.podcast.CreatePodcastDto
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/podcasts")
class PodcastController(private val service: PodcastService) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping
    fun list(): List<Podcast> = service.list(user())

    @PostMapping
    fun create(@RequestBody dto: CreatePodcastDto): ResponseEntity<Podcast> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(user(), dto))

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): Podcast =
        service.get(user(), id) ?: throw NotFoundException("podcast $id")

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        if (!service.delete(user(), id)) throw NotFoundException("podcast $id")
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/audio")
    fun audio(
        @PathVariable id: String,
        @RequestParam(required = false) token: String?,
        @RequestParam(required = false) v: Int?,
        @RequestParam(required = false, defaultValue = "false") download: Boolean
    ): ResponseEntity<ByteArray> {
        val podcast = service.get(user(), id) ?: throw NotFoundException("podcast $id")
        val bytes = service.audioBytes(user(), id) ?: throw NotFoundException("audio $id")
        // Content-Disposition met de podcast-titel als filename: zo heet
        // het opgeslagen MP3-bestand bij download "DevTalk 12, 2026-05-08
        // — Kotlin, Flutter.mp3" i.p.v. "audio?token=...".
        //
        // Twee modi:
        //   ?download=1  → "attachment" — browser triggert echte download
        //                  i.p.v. inline-player te openen.
        //   default      → "inline" — geschikt voor just_audio streamen
        //                  in de app; tab-open in een browser speelt af.
        // De filename-hint geldt in beide modi.
        val filename = sanitizeFilename(podcast.title.ifBlank { "DevTalk-$id" }) + ".mp3"
        // ContentDisposition.builder zorgt voor RFC 5987 encoding van
        // non-ASCII tekens (em-dash, accenten) — anders stript Tomcat
        // de hele header omdat HTTP-headers default ASCII-only zijn.
        // Resultaat: filename="ascii-versie.mp3"; filename*=UTF-8''percent-encoded.mp3
        val cd = if (download) {
            org.springframework.http.ContentDisposition.attachment()
                .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                .build()
        } else {
            org.springframework.http.ContentDisposition.inline()
                .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                .build()
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
            .body(bytes)
    }

    private fun sanitizeFilename(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
}
