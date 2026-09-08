package com.vdzon.newsfeedbackend.auth.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

data class GoogleUser(val email: String, val username: String)

/**
 * Google-loginconfiguratie. Een expliciete e-mail→username-mapping houdt de bestaande
 * per-username data intact wanneer een account van wachtwoord-login naar Google overgaat.
 */
@Component
class GoogleLoginConfig(
    @Value("\${app.auth.google-client-id:}") val clientId: String,
    @Value("\${app.auth.google-users:}") rawUsers: String,
    @Value("\${app.auth.preview-skip-google:false}") val previewSkipGoogle: Boolean,
    @Value("\${app.auth.password-enabled:false}") val passwordAuthEnabled: Boolean,
) {
    val usersByEmail: Map<String, GoogleUser> = parseUsers(rawUsers)
    val allowedUsernames: Set<String> = usersByEmail.values.mapTo(linkedSetOf()) { it.username }

    init {
        if (!previewSkipGoogle && !passwordAuthEnabled) {
            require(clientId.isNotBlank()) { "PNF_GOOGLE_CLIENT_ID is required when Google login is enabled" }
            require(usersByEmail.isNotEmpty()) { "PNF_GOOGLE_USERS must contain at least one email=username mapping" }
        }
    }

    fun userForEmail(email: String): GoogleUser? = usersByEmail[email.trim().lowercase()]

    companion object {
        fun parseUsers(raw: String): Map<String, GoogleUser> = raw
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { entry ->
                val parts = entry.split('=', limit = 2)
                require(parts.size == 2 && parts.all { it.trim().isNotEmpty() }) {
                    "Invalid PNF_GOOGLE_USERS entry '$entry'; expected email=username"
                }
                val email = parts[0].trim().lowercase()
                val username = parts[1].trim()
                require('@' in email) { "Invalid e-mail address in PNF_GOOGLE_USERS: '$email'" }
                email to GoogleUser(email, username)
            }
    }
}
