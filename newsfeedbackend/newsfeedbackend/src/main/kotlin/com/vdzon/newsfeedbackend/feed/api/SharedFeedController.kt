package com.vdzon.newsfeedbackend.feed.api

import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Publieke, read-only feed voor de "reader"-app (Robbert's News Reader).
 * Geeft de gecureerde feed van één vaste gebruiker terug zonder dat de
 * caller hoeft in te loggen — kijkers liften mee op die feed.
 *
 * Bewust géén schrijf-endpoints: gelezen/sterretje houdt de reader-app
 * lokaal op het toestel bij. De persoonlijke read/star/liked-vlaggen van
 * de bron-gebruiker strippen we hier, zodat we z'n leesgedrag niet lekken
 * en de reader met een schone lei begint.
 */
@RestController
@RequestMapping("/api/shared")
class SharedFeedController(
    private val service: FeedService,
    @Value("\${app.shared-feed.username:robbert}") private val sharedUsername: String,
) {

    @GetMapping("/feed")
    fun feed(): List<FeedItem> =
        service.list(sharedUsername).map { it.copy(isRead = false, starred = false, liked = null) }
}
