package com.vdzon.newsfeedbackend.request.api

import com.vdzon.newsfeedbackend.common.NotFoundException
import com.vdzon.newsfeedbackend.request.CreateRequestDto
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/requests")
class RequestController(private val service: RequestService) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping
    fun list(): List<NewsRequest> = service.list(user())

    @PostMapping
    fun create(@RequestBody dto: CreateRequestDto): ResponseEntity<NewsRequest> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(user(), dto))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        if (!service.delete(user(), id)) throw NotFoundException("request $id")
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/rerun")
    fun rerun(@PathVariable id: String): NewsRequest =
        service.rerun(user(), id) ?: throw NotFoundException("request $id")

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: String): ResponseEntity<Void> {
        service.cancel(user(), id)
        return ResponseEntity.noContent().build()
    }
}
