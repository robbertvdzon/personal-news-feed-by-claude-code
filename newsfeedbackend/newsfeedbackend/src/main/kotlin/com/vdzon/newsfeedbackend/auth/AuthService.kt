package com.vdzon.newsfeedbackend.auth

/**
 * Public interface of the auth module.
 */
interface AuthService {
    fun register(username: String, password: String): AuthToken
    fun login(username: String, password: String): AuthToken
    fun userExists(username: String): Boolean
    fun listUsernames(): List<String>
}

data class AuthToken(val token: String, val username: String)
