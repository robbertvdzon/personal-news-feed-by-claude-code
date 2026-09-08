package com.vdzon.newsfeedbackend.e2e

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Settings-endpoints via HTTP: categorieën en RSS-feed-URL's. Alle data
 * is per-user, dus elke test registreert z'n eigen user.
 */
class SettingsE2eTest : E2eTestBase() {

    // ── categorieën ─────────────────────────────────────────────────

    @Test
    fun `eerste GET settings geeft default-categorieen inclusief systeemcategorie`() {
        val user = registerUser("settings")

        val categories = getJson("/api/settings", user.token)
        val ids = categories.values().map { it.path("id").asString() }
        assertEquals(
            listOf("kotlin", "flutter", "ai", "blockchain", "spring", "web_dev", "overig"),
            ids
        )
        assertTrue(categories.all { it.path("enabled").asBoolean() })

        // Alleen "overig" is een systeemcategorie.
        val overig = categories.first { it.path("id").asString() == "overig" }
        assertTrue(overig.path("isSystem").asBoolean())
        assertTrue(categories.filterNot { it.path("id").asString() == "overig" }
            .none { it.path("isSystem").asBoolean() })
    }

    @Test
    fun `categorieen opslaan en teruglezen`() {
        val user = registerUser("settings")

        // NB: complete objecten sturen, zoals de frontend ook doet (die
        // round-tript wat GET teruggaf). Velden weglaten geeft momenteel
        // een 500 omdat Kotlin-defaults niet worden toegepast bij
        // request-body-deserialisatie (zie ook GlobalExceptionHandler).
        val body = """[
            {"id": "kotlin", "name": "Kotlin", "enabled": false, "extraInstructions": "alleen 2.x nieuws", "isSystem": false},
            {"id": "eigen", "name": "Eigen categorie", "enabled": true, "extraInstructions": "", "isSystem": false},
            {"id": "overig", "name": "Overig", "enabled": true, "extraInstructions": "", "isSystem": true}
        ]"""
        val saved = put("/api/settings", user.token, body)
        assertEquals(200, saved.status)

        val categories = getJson("/api/settings", user.token)
        assertEquals(3, categories.size())
        val kotlin = categories.first { it.path("id").asString() == "kotlin" }
        assertFalse(kotlin.path("enabled").asBoolean())
        assertEquals("alleen 2.x nieuws", kotlin.path("extraInstructions").asString())
        assertEquals("Eigen categorie", categories.first { it.path("id").asString() == "eigen" }.path("name").asString())
    }

    @Test
    fun `systeemcategorie overig wordt bij opslaan altijd terug-toegevoegd`() {
        val user = registerUser("settings")

        // PUT zonder "overig": de service voegt 'm er weer aan toe.
        val saved = put(
            "/api/settings", user.token,
            """[{"id": "kotlin", "name": "Kotlin", "enabled": true, "extraInstructions": "", "isSystem": false}]"""
        )
        assertEquals(200, saved.status)
        val savedIds = saved.json(mapper).values().map { it.path("id").asString() }
        assertEquals(listOf("kotlin", "overig"), savedIds)

        val overig = getJson("/api/settings", user.token).first { it.path("id").asString() == "overig" }
        assertTrue(overig.path("isSystem").asBoolean())
    }

    // ── rss-feeds ───────────────────────────────────────────────────

    @Test
    fun `rss-feeds opslaan en teruglezen`() {
        val user = registerUser("settings")

        // Fresh user: nog geen feeds.
        assertEquals(0, getJson("/api/rss-feeds", user.token).path("feeds").size())

        // example.com/example.org zijn IANA-gereserveerde documentatie-domeinen: altijd echt
        // resolvebaar (in tegenstelling tot de eerder gebruikte verzonnen voorbeeld.nl/ander.nl),
        // nodig sinds SsrfUrlValidator (SF-1345) DNS-resolutie afdwingt bij het opslaan.
        val saved = put(
            "/api/rss-feeds", user.token,
            """{"feeds": ["https://example.com/feed.xml", "https://example.org/rss"]}"""
        )
        assertEquals(200, saved.status)

        val feeds = getJson("/api/rss-feeds", user.token).path("feeds").values().map { it.asString() }
        assertEquals(listOf("https://example.com/feed.xml", "https://example.org/rss"), feeds)
    }

    @Test
    fun `settings-endpoints weigeren zonder token`() {
        for (path in listOf(
            "/api/settings",
            "/api/rss-feeds"
        )) {
            val resp = get(path)
            assertTrue(resp.status in listOf(401, 403), "$path: verwachtte 401/403, kreeg ${resp.status}")
        }
    }
    // ── Jackson 3 Kotlin-defaults (regressie) ───────────────────────

    /**
     * Regressietest voor de HTTP-deserialisatie: Spring Boot 4 gebruikt
     * Jackson 3 in de webconverter; zonder tools.jackson-kotlin-module
     * gaf een weggelaten optioneel veld (isSystem/extraInstructions) een
     * 500 "Cannot map null into type boolean" i.p.v. de Kotlin-default.
     */
    @Test
    fun `PUT settings met weggelaten optionele velden gebruikt Kotlin-defaults`() {
        val user = registerUser("settings")

        val resp = put(
            "/api/settings", user.token,
            """[{"id": "kotlin", "name": "Kotlin"}]"""
        )
        assertEquals(200, resp.status, "verwachtte 200, kreeg ${resp.status}: ${resp.body}")

        val saved = getJson("/api/settings", user.token)
        val kotlinCat = saved.first { it.path("id").asString() == "kotlin" }
        assertTrue(kotlinCat.path("enabled").asBoolean(), "enabled hoort default true te zijn")
        assertFalse(kotlinCat.path("isSystem").asBoolean(), "isSystem hoort default false te zijn")
        assertEquals("", kotlinCat.path("extraInstructions").asString())
    }
}
