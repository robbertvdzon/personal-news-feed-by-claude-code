package com.vdzon.newsfeedbackend.settings

import java.time.Instant

interface SettingsService {
    fun getCategories(username: String): List<CategorySettings>
    fun saveCategories(username: String, categories: List<CategorySettings>): List<CategorySettings>

    fun getRssFeeds(username: String): RssFeedsSettings
    fun saveRssFeeds(username: String, settings: RssFeedsSettings): RssFeedsSettings

    fun getPodcastFeeds(username: String): PodcastFeedsSettings
    fun savePodcastFeeds(username: String, settings: PodcastFeedsSettings): PodcastFeedsSettings

    /**
     * KAN-68: vrije lijst event-namen die de gebruiker wil laten volgen
     * door de wekelijkse event-discovery (primaire seed). Bij eerste
     * GET — als de gebruiker nog geen lijst heeft — wordt een sensible
     * default geïnitialiseerd (JavaOne, KotlinConf, Spring I/O, Code
     * with Claude, OpenAI DevDay, Google I/O, Devoxx, KubeCon).
     */
    fun getEventPreferences(username: String): EventPreferences
    fun saveEventPreferences(username: String, settings: EventPreferences): EventPreferences
    fun addEventPreference(username: String, name: String): EventPreferences
    fun removeEventPreference(username: String, name: String): EventPreferences

    /**
     * KAN-68: per-user denylist met genormaliseerde event-ids van
     * eerder verwijderde events. De discovery slaat events op de
     * denylist over; de gebruiker kan ze via Settings weer
     * terugzetten (remove → eerstvolgende discovery vindt 'm weer).
     */
    fun getEventDenylist(username: String): EventDenylist
    fun addEventToDenylist(username: String, normalizedId: String, name: String): Boolean
    fun removeEventFromDenylist(username: String, normalizedId: String): EventDenylist
}

data class CategorySettings(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val extraInstructions: String = "",
    @get:com.fasterxml.jackson.annotation.JsonProperty("isSystem")
    @field:com.fasterxml.jackson.annotation.JsonProperty("isSystem")
    @param:com.fasterxml.jackson.annotation.JsonProperty("isSystem")
    val isSystem: Boolean = false
)

data class RssFeedsSettings(val feeds: List<String> = emptyList())

/**
 * KAN-56: per-user lijst met podcast-RSS-bronnen.
 * Per bron toggle voor "transcriberen aan/uit": wanneer uit, valt de
 * pipeline terug op de show-notes (de RSS-`description`) als input voor
 * Claude, zonder Whisper-kosten te maken.
 */
data class PodcastFeedsSettings(val feeds: List<PodcastFeed> = emptyList())

data class PodcastFeed(
    val url: String,
    val transcribeEnabled: Boolean = true
)

/**
 * KAN-68: per-user lijst event-namen die als seed voor de discovery
 * dienen. Vrije tekst (geen autocomplete) — de naam is meteen ook de
 * Tavily-zoekterm. De volgorde komt 1:1 uit de DB (sort_order op
 * insert-volgorde).
 */
data class EventPreferences(val names: List<String> = emptyList())

/**
 * KAN-68: één rij op de denylist. `normalizedId` is de slug-vorm
 * (zelfde als `events.id`, bv. `javaone-2026`), `name` is een
 * snapshot van de display-naam op het moment van verwijderen zodat
 * de Settings-UI iets leesbaars kan tonen.
 */
data class EventDenylistEntry(
    val normalizedId: String,
    val name: String,
    val addedAt: Instant
)

data class EventDenylist(val entries: List<EventDenylistEntry> = emptyList())
