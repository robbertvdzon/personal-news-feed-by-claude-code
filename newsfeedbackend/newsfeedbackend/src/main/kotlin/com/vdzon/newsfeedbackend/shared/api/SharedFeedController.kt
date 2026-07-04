package com.vdzon.newsfeedbackend.shared.api

import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.shared.api.dto.SharedFeedItemDto
import com.vdzon.newsfeedbackend.shared.api.dto.toSharedDto
import com.vdzon.newsfeedbackend.settings.SettingsService
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
 * de bron-gebruiker zitten helemaal niet in [SharedFeedItemDto], zodat we
 * z'n leesgedrag niet lekken en de reader met een schone lei begint.
 */
@RestController
@RequestMapping("/api/shared")
class SharedFeedController(
    private val service: FeedService,
    private val settingsService: SettingsService,
    @param:Value("\${app.shared-feed.username:robbert}") private val sharedUsername: String,
) {

    @GetMapping("/feed")
    fun feed(): List<SharedFeedItemDto> =
        service.list(sharedUsername).map { it.toSharedDto() }

    /**
     * Categorie-instellingen van de bron-gebruiker, zodat de reader-app de
     * categorie-tabjes met nette namen + volgorde kan tonen. Read-only,
     * geen auth — alleen ingeschakelde categorieën zijn relevant voor de UI.
     */
    @GetMapping("/categories")
    fun categories(): List<CategorySettings> =
        settingsService.getCategories(sharedUsername).filter { it.enabled }
}
