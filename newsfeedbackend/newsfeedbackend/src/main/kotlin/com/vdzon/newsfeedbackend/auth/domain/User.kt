package com.vdzon.newsfeedbackend.auth.domain

/**
 * Domeinmodel voor een gebruiker.
 *
 * `role` is "user" (default) of "admin". Een admin-gebruiker mag andere
 * gebruikers beheren via de /api/admin/-endpoints.
 *
 * Het veld is optioneel in de Jackson-deserialisatie zodat oude users.json
 * bestanden zonder role-veld nog ingeladen kunnen worden — die krijgen dan
 * stilzwijgend de default "user".
 */
data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val role: String = ROLE_USER
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ADMIN = "admin"
    }
}
