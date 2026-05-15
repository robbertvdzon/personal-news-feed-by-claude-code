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

    /**
     * Verwijdert het account van de ingelogde user. Authenticatie via JWT
     * is bewijs genoeg — geen password-verify (zou test-scripts complex
     * maken die zichzelf opruimen). Idempotent: dubbele delete is geen
     * fout, returnt of er iets is verwijderd.
     */
    fun deleteOwnAccount(username: String): Boolean
}

data class AuthToken(val token: String, val username: String, val role: String)
