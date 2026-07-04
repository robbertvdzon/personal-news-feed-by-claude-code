package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Publieke shared-feed voor de reader-app: geen auth, altijd de feed
 * van de geconfigureerde shared-user (property `app.shared-feed.username`,
 * default "robbert"). Die user bestaat niet vanzelf in de test-DB, dus
 * elke test zorgt eerst dat 'ie er is ([ensureSharedUser] — idempotent,
 * want de tests in deze klasse delen dezelfde JVM/DB). Feed-items seeden
 * we via de publieke module-API [FeedService]; de RSS-pipeline zelf is al
 * gedekt in [RssRefreshE2eTest].
 */
class SharedFeedE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var feedService: FeedService

    @Autowired
    private lateinit var settingsService: SettingsService

    private val sharedUsername = "robbert"

    /** Registreert de shared-user; bestaat 'ie al (eerdere test in deze JVM) dan is dat prima. */
    private fun ensureSharedUser() {
        val resp = post(
            "/api/auth/register",
            body = """{"username": "$sharedUsername", "password": "geheim123"}"""
        )
        check(resp.status == 201 || resp.status == 409) {
            "registratie shared-user faalde: ${resp.status} ${resp.body}"
        }
    }

    private fun seed(username: String, item: FeedItem): FeedItem = feedService.save(username, item)

    private fun newItem(
        title: String,
        isRead: Boolean = false,
        starred: Boolean = false,
        liked: Boolean? = null
    ) = FeedItem(
        id = UUID.randomUUID().toString(),
        title = title,
        summary = "samenvatting van $title",
        isRead = isRead,
        starred = starred,
        liked = liked
    )

    @Test
    fun `shared feed en categories zijn zonder token bereikbaar`() {
        ensureSharedUser()

        assertEquals(200, get("/api/shared/feed").status)
        assertEquals(200, get("/api/shared/categories").status)
    }

    @Test
    fun `shared feed toont de items van de shared-user maar niet die van anderen`() {
        ensureSharedUser()
        val ander = registerUser("ander")
        val eigenTitel = "Gedeeld item ${UUID.randomUUID().toString().take(8)}"
        val andermansTitel = "Prive item ${UUID.randomUUID().toString().take(8)}"
        seed(sharedUsername, newItem(eigenTitel))
        seed(ander.username, newItem(andermansTitel))

        val titels = getJson("/api/shared/feed").map { it.path("title").asText() }
        assertTrue(eigenTitel in titels)
        assertFalse(andermansTitel in titels)
    }

    @Test
    fun `persoonlijke vlaggen van de shared-user zijn gestript in de response`() {
        ensureSharedUser()
        val titel = "Gelezen met ster ${UUID.randomUUID().toString().take(8)}"
        seed(sharedUsername, newItem(titel, isRead = true, starred = true, liked = true))

        val item = getJson("/api/shared/feed").first { it.path("title").asText() == titel }
        // Het leesgedrag van de bron-gebruiker mag niet lekken: de reader
        // begint met een schone lei.
        assertFalse(item.path("isRead").asBoolean())
        assertFalse(item.path("starred").asBoolean())
        assertTrue(item.path("liked").isNull || item.path("liked").isMissingNode)
        // De inhoud zelf is wel gewoon aanwezig.
        assertEquals("samenvatting van $titel", item.path("summary").asText())
    }

    @Test
    fun `alleen enabled categorieen van de shared-user komen terug`() {
        ensureSharedUser()
        settingsService.saveCategories(
            sharedUsername,
            listOf(
                CategorySettings("kotlin", "Kotlin", enabled = true),
                CategorySettings("flutter", "Flutter", enabled = false),
                CategorySettings("overig", "Overig", enabled = true, isSystem = true)
            )
        )

        val ids = getJson("/api/shared/categories").map { it.path("id").asText() }
        assertTrue("kotlin" in ids)
        assertTrue("overig" in ids)
        assertFalse("flutter" in ids)
    }
}
