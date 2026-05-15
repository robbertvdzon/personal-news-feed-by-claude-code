package com.vdzon.newsfeedbackend.auth.api

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * "Self-service"-endpoints voor de ingelogde user — los van /api/auth dat
 * juist publieke (login/register) bevat. Hier komt alles wat een
 * authenticated user op zijn eigen account mag doen.
 */
@RestController
@RequestMapping("/api/account")
class AccountController(private val auth: AuthService) {

    @PutMapping("/password")
    fun changePassword(@RequestBody body: ChangePasswordRequest): Map<String, String> {
        val username = SecurityHelpers.currentUsername()
        auth.changePassword(username, body.currentPassword, body.newPassword)
        return mapOf("status" to "ok")
    }

    /**
     * Verwijdert het eigen account. Authenticatie via JWT is bewijs
     * genoeg — geen extra password-verify (de tester-agent ruimt zo
     * z'n wegwerp-accounts op zonder password-management in z'n script).
     */
    @DeleteMapping("/me")
    fun deleteMe(): Map<String, Any> {
        val username = SecurityHelpers.currentUsername()
        val deleted = auth.deleteOwnAccount(username)
        return mapOf("status" to "ok", "deleted" to deleted, "username" to username)
    }
}

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)
