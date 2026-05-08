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
        @RequestParam(required = false) v: Int?
    ): ResponseEntity<ByteArray> {
        val podcast = service.get(user(), id) ?: throw NotFoundException("podcast $id")
        val bytes = service.audioBytes(user(), id) ?: throw NotFoundException("audio $id")
        // Content-Disposition met de podcast-titel als filename: zo heet
        // het opgeslagen MP3-bestand bij download "DevTalk 12, 2026-05-08
        // — Kotlin, Flutter.mp3" i.p.v. "audio?token=...". Browsers en
        // Android-handlers honoreren deze hint bij Save As of in de
        // Downloads-folder. inline (geen attachment) zodat just_audio
        // streamen niet beïnvloed wordt.
        val filename = sanitizeFilename(podcast.title.ifBlank { "DevTalk-$id" }) + ".mp3"
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$filename\"")
            .body(bytes)
    }

    private fun sanitizeFilename(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
}
