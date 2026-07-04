package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.auth.AuthService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Admin-endpoints via HTTP. De rol zit in het JWT, dus na een
 * rolwijziging is opnieuw inloggen nodig voor een token met de nieuwe
 * rol — de tests hieronder doen dat expliciet.
 *
 * Admin-rechten regelen we betrouwbaar: de éérste registratie van deze
 * test-JVM loopt altijd via [firstRegistered] (elke test haalt eerst
 * z'n admin op vóór 'ie andere users registreert), en voor de
 * zekerheid zetten we de rol daarna nog expliciet via de publieke
 * module-API [AuthService] — zo blijft de suite groen óók als er ooit
 * eerder in dezelfde JVM al een admin zou bestaan.
 */
class AdminE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var authService: AuthService

    data class FirstUser(val user: TestUser, val registeredRole: String)

    companion object {
        private var firstUser: FirstUser? = null
    }

    /** Registreert (eenmalig per JVM) de allereerste user en onthoudt de rol uit de register-response. */
    private fun firstRegistered(): FirstUser {
        firstUser?.let { return it }
        val username = uniqueUsername("admin")
        val password = "geheim123"
        val resp = post("/api/auth/register", body = """{"username": "$username", "password": "$password"}""")
        check(resp.status == 201) { "register faalde: ${resp.status} ${resp.body}" }
        val json = resp.json(mapper)
        val created = FirstUser(
            TestUser(username, password, json.path("token").asText()),
            json.path("role").asText()
        )
        firstUser = created
        return created
    }

    /** Geeft een user met admin-rol én een token waar die rol in zit. */
    private fun admin(): TestUser {
        val first = firstRegistered()
        // Expliciet de rol zetten (no-op als 'ie al admin is) en opnieuw
        // inloggen: de rol zit in het JWT, niet in de DB-lookup per request.
        authService.setRole(first.user.username, AuthService.ROLE_ADMIN)
        val login = post(
            "/api/auth/login",
            body = """{"username": "${first.user.username}", "password": "${first.user.password}"}"""
        )
        check(login.status == 200) { "admin-login faalde: ${login.status} ${login.body}" }
        return first.user.copy(token = login.json(mapper).path("token").asText())
    }

    @Test
    fun `eerste geregistreerde user in een vers systeem krijgt admin-rol`() {
        // Failsafe forkt per testklasse, dus deze JVM begon met een lege DB;
        // firstRegistered() was gegarandeerd de allereerste registratie.
        val first = firstRegistered()
        assertEquals("admin", first.registeredRole)
    }

    @Test
    fun `niet-admin krijgt 403 op alle admin-endpoints`() {
        admin() // zorgt dat er al een admin bestaat, zodat de volgende user rol=user krijgt
        val user = registerUser("gewoon")

        assertEquals(403, get("/api/admin/users", user.token).status)
        assertEquals(403, put("/api/admin/users/iemand/password", user.token, """{"newPassword": "x1234"}""").status)
        assertEquals(403, put("/api/admin/users/iemand/role", user.token, """{"role": "admin"}""").status)
        assertEquals(403, delete("/api/admin/users/iemand", user.token).status)
        assertEquals(403, get("/api/admin/costs/totals", user.token).status)
    }

    @Test
    fun `admin ziet user-lijst met rollen`() {
        val admin = admin()
        val user = registerUser("lijst")

        val users = getJson("/api/admin/users", admin.token)
        val byName = users.associate { it.path("username").asText() to it.path("role").asText() }
        assertEquals("admin", byName[admin.username])
        assertEquals("user", byName[user.username])
        assertTrue(users.all { it.path("id").asText().isNotBlank() })
    }

    @Test
    fun `admin reset wachtwoord - user logt daarna in met het nieuwe wachtwoord`() {
        val admin = admin()
        val user = registerUser("reset")

        val resp = put(
            "/api/admin/users/${user.username}/password", admin.token,
            """{"newPassword": "nieuwwachtwoord1"}"""
        )
        assertEquals(200, resp.status)
        assertEquals("ok", resp.json(mapper).path("status").asText())

        // Oud wachtwoord werkt niet meer, nieuw wel.
        assertEquals(
            401,
            post("/api/auth/login", body = """{"username": "${user.username}", "password": "${user.password}"}""").status
        )
        assertEquals(
            200,
            post("/api/auth/login", body = """{"username": "${user.username}", "password": "nieuwwachtwoord1"}""").status
        )
    }

    @Test
    fun `admin wijzigt rol - promotie werkt na herinloggen, demotie ook weer`() {
        val admin = admin()
        val user = registerUser("rol")

        // Zonder admin-rol: geen toegang.
        assertEquals(403, get("/api/admin/users", user.token).status)

        // Promoveren; rol zit in het JWT dus opnieuw inloggen.
        assertEquals(
            200,
            put("/api/admin/users/${user.username}/role", admin.token, """{"role": "admin"}""").status
        )
        val alsAdmin = post(
            "/api/auth/login",
            body = """{"username": "${user.username}", "password": "${user.password}"}"""
        ).json(mapper)
        assertEquals("admin", alsAdmin.path("role").asText())
        assertEquals(200, get("/api/admin/users", alsAdmin.path("token").asText()).status)

        // Demoveren (door de oorspronkelijke admin, dus geen self-demote).
        assertEquals(
            200,
            put("/api/admin/users/${user.username}/role", admin.token, """{"role": "user"}""").status
        )
        val alsUser = post(
            "/api/auth/login",
            body = """{"username": "${user.username}", "password": "${user.password}"}"""
        ).json(mapper)
        assertEquals("user", alsUser.path("role").asText())
        assertEquals(403, get("/api/admin/users", alsUser.path("token").asText()).status)

        // Onbekende rol wordt geweigerd.
        assertEquals(
            400,
            put("/api/admin/users/${user.username}/role", admin.token, """{"role": "superuser"}""").status
        )
    }

    @Test
    fun `guardrail - eigen admin-rol verwijderen geeft 400`() {
        val admin = admin()

        val resp = put(
            "/api/admin/users/${admin.username}/role", admin.token,
            """{"role": "user"}"""
        )
        assertEquals(400, resp.status)
        assertTrue(resp.json(mapper).path("error").asText().isNotBlank())

        // Admin is nog steeds admin.
        assertEquals(200, get("/api/admin/users", admin.token).status)
    }

    @Test
    fun `guardrail - jezelf verwijderen geeft 400`() {
        val admin = admin()

        val resp = delete("/api/admin/users/${admin.username}", admin.token)
        assertEquals(400, resp.status)

        // Account bestaat nog gewoon.
        assertEquals(
            200,
            post("/api/auth/login", body = """{"username": "${admin.username}", "password": "${admin.password}"}""").status
        )
    }

    @Test
    fun `admin verwijdert andere user - login daarna onmogelijk`() {
        val admin = admin()
        val user = registerUser("weg")

        val resp = delete("/api/admin/users/${user.username}", admin.token)
        assertEquals(200, resp.status)

        assertEquals(
            401,
            post("/api/auth/login", body = """{"username": "${user.username}", "password": "${user.password}"}""").status
        )
        val names = getJson("/api/admin/users", admin.token).map { it.path("username").asText() }
        assertFalse(user.username in names)

        // Onbekende user verwijderen geeft 404.
        assertEquals(404, delete("/api/admin/users/${user.username}", admin.token).status)
    }

    @Test
    fun `kosten-endpoints geven 200 met de verwachte vorm`() {
        val admin = admin()

        val totals = get("/api/admin/costs/totals", admin.token)
        assertEquals(200, totals.status)
        val totalsJson = totals.json(mapper)
        for (field in listOf("today", "thisMonth", "thisYear", "all", "callCountAll")) {
            assertTrue(totalsJson.has(field), "totals mist veld '$field': ${totals.body}")
        }

        val daily = get("/api/admin/costs/daily?days=7", admin.token)
        assertEquals(200, daily.status)
        assertTrue(daily.json(mapper).isArray)

        val byUser = get("/api/admin/costs/by-user?period=this_month", admin.token)
        assertEquals(200, byUser.status)
        assertTrue(byUser.json(mapper).isArray)

        val calls = get("/api/admin/costs/calls?limit=10", admin.token)
        assertEquals(200, calls.status)
        assertTrue(calls.json(mapper).isArray)
    }

    @Test
    fun `kosten-endpoints valideren hun parameters`() {
        val admin = admin()

        assertEquals(400, get("/api/admin/costs/daily?days=0", admin.token).status)
        assertEquals(400, get("/api/admin/costs/daily?days=999", admin.token).status)
        assertEquals(400, get("/api/admin/costs/by-user?period=bogus", admin.token).status)
        assertEquals(400, get("/api/admin/costs/calls?from=geen-timestamp", admin.token).status)
    }
}
