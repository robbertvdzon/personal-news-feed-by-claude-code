package com.vdzon.newsfeedbackend.auth

/**
 * Public interface of the auth module.
 */
interface AuthService {
    fun register(username: String, password: String): AuthToken
    fun login(username: String, password: String): AuthToken
    fun userExists(username: String): Boolean
    fun listUsernames(): List<String>
    /**
     * Wijzigt het wachtwoord van een ingelogde user. Verifieert eerst het
     * huidige wachtwoord — wie zijn token heeft maar zijn wachtwoord niet
     * weet (bv. een gestolen device) kan het wachtwoord dus niet veranderen.
     */
    fun changePassword(username: String, currentPassword: String, newPassword: String)
}

data class AuthToken(val token: String, val username: String, val role: String)
