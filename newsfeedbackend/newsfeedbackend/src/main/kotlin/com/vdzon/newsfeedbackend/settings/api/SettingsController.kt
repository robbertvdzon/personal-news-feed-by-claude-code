package com.vdzon.newsfeedbackend.settings.api

import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.EventDenylist
import com.vdzon.newsfeedbackend.settings.EventPreferences
import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import com.vdzon.newsfeedbackend.settings.api.dto.AddEventPreferenceRequest
import com.vdzon.newsfeedbackend.settings.api.dto.RemoveEventPreferenceRequest
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class SettingsController(
    private val service: SettingsService
) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping("/api/settings")
    fun getSettings(): List<CategorySettings> = service.getCategories(user())

    @PutMapping("/api/settings")
    fun saveSettings(@RequestBody body: List<CategorySettings>): List<CategorySettings> =
        service.saveCategories(user(), body)

    @GetMapping("/api/rss-feeds")
    fun getRssFeeds(): RssFeedsSettings = service.getRssFeeds(user())

    @PutMapping("/api/rss-feeds")
    fun saveRssFeeds(@RequestBody body: RssFeedsSettings): RssFeedsSettings =
        service.saveRssFeeds(user(), body)

    // ── KAN-68: event-voorkeuren ────────────────────────────────────

    @GetMapping("/api/settings/event-preferences")
    fun getEventPreferences(): EventPreferences = service.getEventPreferences(user())

    /**
     * Vervangt de volledige lijst event-voorkeuren in één call. Lege
     * en duplicaat-namen worden door de service genegeerd.
     */
    @PutMapping("/api/settings/event-preferences")
    fun saveEventPreferences(@RequestBody body: EventPreferences): EventPreferences =
        service.saveEventPreferences(user(), body)

    /**
     * Voegt één naam toe. Idempotent — bestaat 'ie al dan blijft de
     * lijst onveranderd. Returnt de actuele lijst zodat de frontend
     * niet apart hoeft te herfetchen.
     */
    @PostMapping("/api/settings/event-preferences")
    fun addEventPreference(@RequestBody body: AddEventPreferenceRequest): EventPreferences {
        val name = body.name.trim()
        if (name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Naam mag niet leeg zijn")
        }
        return service.addEventPreference(user(), name)
    }

    /**
     * Verwijder één event-voorkeur. Naam in de request body i.p.v. als
     * path-segment omdat default-namen als "Spring I/O" en "Google I/O"
     * een `/` bevatten; Spring/Tomcat strippen `%2F` standaard waardoor
     * een path-variabele de DELETE op die namen onbereikbaar maakte.
     */
    @PostMapping("/api/settings/event-preferences/remove")
    fun removeEventPreference(@RequestBody body: RemoveEventPreferenceRequest): EventPreferences {
        val name = body.name.trim()
        if (name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Naam mag niet leeg zijn")
        }
        return service.removeEventPreference(user(), name)
    }

    // ── KAN-68: event-denylist ──────────────────────────────────────

    @GetMapping("/api/settings/event-denylist")
    fun getEventDenylist(): EventDenylist = service.getEventDenylist(user())

    /**
     * Haal één id van de denylist af. De eerstvolgende discovery-run
     * vindt 'm dan weer als seed/match.
     */
    @DeleteMapping("/api/settings/event-denylist/{normalizedId}")
    fun removeFromEventDenylist(@PathVariable normalizedId: String): EventDenylist =
        service.removeEventFromDenylist(user(), normalizedId)
}
