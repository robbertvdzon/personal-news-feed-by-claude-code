package com.vdzon.newsfeedbackend.auth.api

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.auth.api.dto.AuthRequest
import com.vdzon.newsfeedbackend.auth.api.dto.AuthResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    fun register(@RequestBody body: AuthRequest): ResponseEntity<AuthResponse> {
        val token = authService.register(body.username, body.password)
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse(token.token, token.username))
    }

    @PostMapping("/login")
    fun login(@RequestBody body: AuthRequest): ResponseEntity<AuthResponse> {
        val token = authService.login(body.username, body.password)
        return ResponseEntity.ok(AuthResponse(token.token, token.username))
    }
}
