package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.settings.SettingsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Settings-endpoints via HTTP: categorieën, RSS-feed-URL's en de
 * KAN-68 event-voorkeuren/-denylist. Alle data is per-user, dus elke
 * test registreert z'n eigen user. Voor de denylist is er geen publiek
 * "toevoegen"-endpoint (dat doet de event-module intern); daar seeden
 * we via de publieke module-API [SettingsService].
 */
class SettingsE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var settingsService: SettingsService

    // ── categorieën ─────────────────────────────────────────────────

    @Test
    fun `eerste GET settings geeft default-categorieen inclusief systeemcategorie`() {
        val user = registerUser("settings")

        val categories = getJson("/api/settings", user.token)
        val ids = categories.map { it.path("id").asText() }
        assertEquals(
            listOf("kotlin", "flutter", "ai", "blockchain", "spring", "web_dev", "overig"),
            ids
        )
        assertTrue(categories.all { it.path("enabled").asBoolean() })

        // Alleen "overig" is een systeemcategorie.
        val overig = categories.first { it.path("id").asText() == "overig" }
        assertTrue(overig.path("isSystem").asBoolean())
        assertTrue(categories.filterNot { it.path("id").asText() == "overig" }
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
        val kotlin = categories.first { it.path("id").asText() == "kotlin" }
        assertFalse(kotlin.path("enabled").asBoolean())
        assertEquals("alleen 2.x nieuws", kotlin.path("extraInstructions").asText())
        assertEquals("Eigen categorie", categories.first { it.path("id").asText() == "eigen" }.path("name").asText())
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
        val savedIds = saved.json(mapper).map { it.path("id").asText() }
        assertEquals(listOf("kotlin", "overig"), savedIds)

        val overig = getJson("/api/settings", user.token).first { it.path("id").asText() == "overig" }
        assertTrue(overig.path("isSystem").asBoolean())
    }

    // ── rss-feeds ───────────────────────────────────────────────────

    @Test
    fun `rss-feeds opslaan en teruglezen`() {
        val user = registerUser("settings")

        // Fresh user: nog geen feeds.
        assertEquals(0, getJson("/api/rss-feeds", user.token).path("feeds").size())

        val saved = put(
            "/api/rss-feeds", user.token,
            """{"feeds": ["https://voorbeeld.nl/feed.xml", "https://ander.nl/rss"]}"""
        )
        assertEquals(200, saved.status)

        val feeds = getJson("/api/rss-feeds", user.token).path("feeds").map { it.asText() }
        assertEquals(listOf("https://voorbeeld.nl/feed.xml", "https://ander.nl/rss"), feeds)
    }

    // ── event-voorkeuren (KAN-68) ───────────────────────────────────

    @Test
    fun `eerste GET event-preferences initialiseert de default-lijst`() {
        val user = registerUser("settings")

        val names = getJson("/api/settings/event-preferences", user.token).path("names").map { it.asText() }
        assertEquals(
            listOf(
                "JavaOne", "KotlinConf", "Spring I/O", "Code with Claude",
                "OpenAI DevDay", "Google I/O", "Devoxx", "KubeCon"
            ),
            names
        )
    }

    @Test
    fun `PUT event-preferences vervangt de lijst en schoont trim, lege en duplicaat-namen op`() {
        val user = registerUser("settings")

        val saved = put(
            "/api/settings/event-preferences", user.token,
            """{"names": ["  MijnConf  ", "", "AndereConf", "MijnConf"]}"""
        )
        assertEquals(200, saved.status)
        assertEquals(listOf("MijnConf", "AndereConf"), saved.json(mapper).path("names").map { it.asText() })

        // Teruglezen geeft dezelfde (vervangen) lijst — geen defaults meer.
        val names = getJson("/api/settings/event-preferences", user.token).path("names").map { it.asText() }
        assertEquals(listOf("MijnConf", "AndereConf"), names)
    }

    @Test
    fun `POST event-preference voegt idempotent toe`() {
        val user = registerUser("settings")
        // Eerst GET zodat de default-lijst geinitialiseerd is.
        val defaults = getJson("/api/settings/event-preferences", user.token).path("names").map { it.asText() }

        val added = post(
            "/api/settings/event-preferences", user.token,
            """{"name": "MijnConf"}"""
        )
        assertEquals(200, added.status)
        assertEquals(defaults + "MijnConf", added.json(mapper).path("names").map { it.asText() })

        // Nogmaals dezelfde naam: lijst blijft onveranderd.
        val again = post(
            "/api/settings/event-preferences", user.token,
            """{"name": "MijnConf"}"""
        )
        assertEquals(200, again.status)
        assertEquals(defaults + "MijnConf", again.json(mapper).path("names").map { it.asText() })
    }

    @Test
    fun `POST event-preference met lege naam geeft 400`() {
        val user = registerUser("settings")

        val resp = post(
            "/api/settings/event-preferences", user.token,
            """{"name": "   "}"""
        )
        assertEquals(400, resp.status)
        assertTrue(resp.json(mapper).path("error").asText().isNotBlank())
    }

    @Test
    fun `POST remove verwijdert een event-voorkeur, ook namen met een slash`() {
        val user = registerUser("settings")
        getJson("/api/settings/event-preferences", user.token) // defaults initialiseren

        // "Spring I/O" bevat een slash — dáárom is remove een POST met body.
        val resp = post(
            "/api/settings/event-preferences/remove", user.token,
            """{"name": "Spring I/O"}"""
        )
        assertEquals(200, resp.status)
        val names = resp.json(mapper).path("names").map { it.asText() }
        assertFalse("Spring I/O" in names)
        assertTrue("KotlinConf" in names)

        // Lege naam ook hier een 400.
        assertEquals(
            400,
            post("/api/settings/event-preferences/remove", user.token, """{"name": ""}""").status
        )
    }

    // ── event-denylist (KAN-68) ─────────────────────────────────────

    @Test
    fun `event-denylist tonen en entry verwijderen via DELETE`() {
        val user = registerUser("settings")

        // Fresh user: lege denylist.
        assertEquals(0, getJson("/api/settings/event-denylist", user.token).path("entries").size())

        // Toevoegen gebeurt normaal door de event-module bij het verwijderen
        // van een event; er is bewust geen publiek POST-endpoint. Seed via
        // de publieke module-API.
        assertTrue(settingsService.addEventToDenylist(user.username, "javaone-2026", "JavaOne 2026"))

        val entries = getJson("/api/settings/event-denylist", user.token).path("entries")
        assertEquals(1, entries.size())
        assertEquals("javaone-2026", entries[0].path("normalizedId").asText())
        assertEquals("JavaOne 2026", entries[0].path("name").asText())
        assertTrue(entries[0].path("addedAt").asText().isNotBlank())

        // DELETE haalt 'm er weer af en returnt de actuele (lege) lijst.
        val deleted = delete("/api/settings/event-denylist/javaone-2026", user.token)
        assertEquals(200, deleted.status)
        assertEquals(0, deleted.json(mapper).path("entries").size())
        assertEquals(0, getJson("/api/settings/event-denylist", user.token).path("entries").size())
    }

    @Test
    fun `settings-endpoints weigeren zonder token`() {
        for (path in listOf(
            "/api/settings",
            "/api/rss-feeds",
            "/api/settings/event-preferences",
            "/api/settings/event-denylist"
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
        val kotlinCat = saved.first { it.path("id").asText() == "kotlin" }
        assertTrue(kotlinCat.path("enabled").asBoolean(), "enabled hoort default true te zijn")
        assertFalse(kotlinCat.path("isSystem").asBoolean(), "isSystem hoort default false te zijn")
        assertEquals("", kotlinCat.path("extraInstructions").asText())
    }
}
