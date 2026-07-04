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

    // ── Beheer-operaties (gebruikt door de admin-module) ─────────────
    // Autorisatie en guardrails (mag deze actor dit?) horen bij de
    // caller; deze methodes voeren alleen de mutatie uit.

    fun listAccounts(): List<UserAccount>
    fun findAccount(username: String): UserAccount?
    /** Zet een nieuw wachtwoord zonder het oude te verifiëren (admin-reset). */
    fun resetPassword(username: String, newPassword: String)
    fun setRole(username: String, role: String)
    fun deleteUser(username: String): Boolean

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ADMIN = "admin"
    }
}

data class AuthToken(val token: String, val username: String, val role: String)

/** Publieke, wachtwoord-loze weergave van een account (voor beheer-schermen). */
data class UserAccount(val id: String, val username: String, val role: String)
