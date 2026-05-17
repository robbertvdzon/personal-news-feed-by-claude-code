package com.vdzon.newsfeedbackend.test.api

import com.vdzon.newsfeedbackend.test.infrastructure.IsolationMarkerRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/test")
class TestController(private val isolationMarkerRepository: IsolationMarkerRepository) {

    @GetMapping("/kan55-isolation-marker")
    fun kan55IsolationMarker(): ResponseEntity<List<Map<String, Any?>>> {
        val records = isolationMarkerRepository.getAllMarkers()
        return ResponseEntity.ok(records)
    }
}
