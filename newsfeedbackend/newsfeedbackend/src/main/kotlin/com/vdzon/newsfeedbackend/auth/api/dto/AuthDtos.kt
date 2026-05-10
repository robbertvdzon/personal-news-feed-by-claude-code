package com.vdzon.newsfeedbackend.auth.api.dto

data class AuthRequest(val username: String, val password: String)

data class AuthResponse(val token: String, val username: String, val role: String)
