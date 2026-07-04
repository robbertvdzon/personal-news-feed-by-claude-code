package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Feed-gedrag via HTTP. Feed-items ontstaan normaal via de RSS-pipeline
 * (zie [RssRefreshE2eTest]); hier seeden we ze rechtstreeks via de
 * publieke module-API [FeedService] zodat deze test zich op de
 * feed-functionaliteit zelf concentreert.
 */
class FeedE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var feedService: FeedService

    private fun seed(username: String, item: FeedItem): FeedItem = feedService.save(username, item)

    private fun newItem(
        title: String,
        createdAt: Instant = Instant.now(),
        isRead: Boolean = false,
        starred: Boolean = false
    ) = FeedItem(
        id = UUID.randomUUID().toString(),
        title = title,
        summary = "samenvatting van $title",
        createdAt = createdAt,
        isRead = isRead,
        starred = starred
    )

    @Test
    fun `feed lijst toont alleen eigen items, nieuwste eerst`() {
        val user = registerUser("feed")
        val ander = registerUser("ander")
        seed(user.username, newItem("Oud", createdAt = Instant.now().minus(2, ChronoUnit.HOURS)))
        seed(user.username, newItem("Nieuw"))
        seed(ander.username, newItem("Van iemand anders"))

        val items = getJson("/api/feed", user.token)
        assertEquals(2, items.size())
        assertEquals("Nieuw", items[0].path("title").asText())
        assertEquals("Oud", items[1].path("title").asText())
    }

    @Test
    fun `markAllRead markeert alle ongelezen items en telt correct`() {
        val user = registerUser("feed")
        seed(user.username, newItem("A"))
        seed(user.username, newItem("B"))
        seed(user.username, newItem("C", isRead = true))

        val resp = post("/api/feed/markAllRead", user.token)
        assertEquals(200, resp.status)
        assertEquals(2, resp.json(mapper).path("updated").asInt())

        val items = getJson("/api/feed", user.token)
        assertTrue(items.all { it.path("isRead").asBoolean() })

        // Idempotent: tweede keer 0 updates.
        val again = post("/api/feed/markAllRead", user.token)
        assertEquals(0, again.json(mapper).path("updated").asInt())
    }

    @Test
    fun `ster togglen en feedback zetten`() {
        val user = registerUser("feed")
        val item = seed(user.username, newItem("Sterretje"))

        assertEquals(200, put("/api/feed/${item.id}/star", user.token).status)
        var loaded = getJson("/api/feed", user.token)[0]
        assertTrue(loaded.path("starred").asBoolean())

        assertEquals(200, put("/api/feed/${item.id}/star", user.token).status)
        loaded = getJson("/api/feed", user.token)[0]
        assertEquals(false, loaded.path("starred").asBoolean())

        assertEquals(
            200,
            put("/api/feed/${item.id}/feedback", user.token, """{"liked": true}""").status
        )
        loaded = getJson("/api/feed", user.token)[0]
        assertEquals(true, loaded.path("liked").asBoolean())
    }

    @Test
    fun `cleanup verwijdert oude items maar respecteert keep-vlaggen`() {
        val user = registerUser("feed")
        val vijftigDagen = Instant.now().minus(50, ChronoUnit.DAYS)
        seed(user.username, newItem("oud-gelezen", createdAt = vijftigDagen, isRead = true))
        seed(user.username, newItem("oud-met-ster", createdAt = vijftigDagen, isRead = true, starred = true))
        seed(user.username, newItem("oud-ongelezen", createdAt = vijftigDagen, isRead = false))
        seed(user.username, newItem("vers", isRead = true))

        // keepStarred=true, keepUnread=true → alleen "oud-gelezen" weg.
        val resp = delete(
            "/api/feed/cleanup?olderThanDays=30&keepStarred=true&keepLiked=true&keepUnread=true",
            user.token
        )
        assertEquals(200, resp.status)
        assertEquals(1, resp.json(mapper).path("removed").asInt())

        val titels = getJson("/api/feed", user.token).map { it.path("title").asText() }.toSet()
        assertEquals(setOf("oud-met-ster", "oud-ongelezen", "vers"), titels)
    }

    @Test
    fun `item verwijderen`() {
        val user = registerUser("feed")
        val item = seed(user.username, newItem("Weg ermee"))

        assertEquals(200, delete("/api/feed/${item.id}", user.token).status)
        assertEquals(0, getJson("/api/feed", user.token).size())
    }
}
