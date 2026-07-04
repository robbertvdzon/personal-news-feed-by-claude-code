package com.vdzon.newsfeedbackend.e2e

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthE2eTest : E2eTestBase() {

    @Test
    fun `registreren geeft 201 met bruikbaar token`() {
        val user = registerUser()
        assertTrue(user.token.isNotBlank())

        // Token werkt op een beveiligd endpoint.
        assertEquals(200, get("/api/feed", user.token).status)
    }

    @Test
    fun `dubbele registratie geeft 409`() {
        val user = registerUser()
        val again = post(
            "/api/auth/register",
            body = """{"username": "${user.username}", "password": "anderswachtwoord"}"""
        )
        assertEquals(409, again.status)
    }

    @Test
    fun `te kort wachtwoord geeft 400`() {
        val resp = post(
            "/api/auth/register",
            body = """{"username": "${uniqueUsername()}", "password": "abc"}"""
        )
        assertEquals(400, resp.status)
    }

    @Test
    fun `login met juist wachtwoord geeft token, met fout wachtwoord 401`() {
        val user = registerUser()

        val ok = post(
            "/api/auth/login",
            body = """{"username": "${user.username}", "password": "${user.password}"}"""
        )
        assertEquals(200, ok.status)
        assertTrue(ok.json(mapper).path("token").asText().isNotBlank())

        val fout = post(
            "/api/auth/login",
            body = """{"username": "${user.username}", "password": "verkeerd"}"""
        )
        assertEquals(401, fout.status)
    }

    @Test
    fun `beveiligde endpoints weigeren zonder token`() {
        val resp = get("/api/feed")
        assertTrue(resp.status in listOf(401, 403), "verwachtte 401/403, kreeg ${resp.status}")
    }

    @Test
    fun `wachtwoord wijzigen - oud wachtwoord vereist, daarna inloggen met nieuw`() {
        val user = registerUser()

        // Fout huidig wachtwoord → 401, wachtwoord blijft ongewijzigd.
        val geweigerd = put(
            "/api/account/password", user.token,
            """{"currentPassword": "verkeerd", "newPassword": "nieuw1234"}"""
        )
        assertEquals(401, geweigerd.status)

        // Juist huidig wachtwoord → ok.
        val gelukt = put(
            "/api/account/password", user.token,
            """{"currentPassword": "${user.password}", "newPassword": "nieuw1234"}"""
        )
        assertEquals(200, gelukt.status)

        // Oud wachtwoord werkt niet meer, nieuw wel.
        val oud = post(
            "/api/auth/login",
            body = """{"username": "${user.username}", "password": "${user.password}"}"""
        )
        assertEquals(401, oud.status)
        val nieuw = post(
            "/api/auth/login",
            body = """{"username": "${user.username}", "password": "nieuw1234"}"""
        )
        assertEquals(200, nieuw.status)
    }

    @Test
    fun `eigen account verwijderen - token is genoeg, login daarna onmogelijk`() {
        val user = registerUser()
        val resp = delete("/api/account/me", user.token)
        assertEquals(200, resp.status)
        assertEquals(true, resp.json(mapper).path("deleted").asBoolean())

        val login = post(
            "/api/auth/login",
            body = """{"username": "${user.username}", "password": "${user.password}"}"""
        )
        assertEquals(401, login.status)
    }
}
