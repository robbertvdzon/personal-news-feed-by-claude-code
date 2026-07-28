package com.vdzon.newsfeedbackend.settings.domain

import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.common.SsrfUrlValidator
import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.EventDenylist
import com.vdzon.newsfeedbackend.settings.EventPreferences
import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings
import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import com.vdzon.newsfeedbackend.settings.infrastructure.CategorySettingsRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.EventDenylistRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.EventPreferencesRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.PodcastFeedsRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.RssFeedsRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class SettingsServiceImpl(
    private val categoryRepo: CategorySettingsRepository,
    private val rssFeedsRepo: RssFeedsRepository,
    private val podcastFeedsRepo: PodcastFeedsRepository,
    private val eventPreferencesRepo: EventPreferencesRepository,
    private val eventDenylistRepo: EventDenylistRepository,
    // Alleen "true" in de e2e-testomgeving (zie E2eTestBase) — de fake content-server
    // draait per definitie op 127.0.0.1. Ontbreekt de property (elke echte omgeving),
    // dan blijft loopback geblokkeerd zoals SsrfUrlValidator standaard doet.
    @param:Value("\${app.security.ssrf.allow-loopback:false}") private val ssrfAllowLoopback: Boolean = false,
) : SettingsService {

    private val defaultCategories = listOf(
        CategorySettings("kotlin", "Kotlin"),
        CategorySettings("flutter", "Flutter"),
        CategorySettings("ai", "AI"),
        CategorySettings("blockchain", "Blockchain"),
        CategorySettings("spring", "Spring"),
        CategorySettings("web_dev", "Web Development"),
        CategorySettings("overig", "Overig", isSystem = true)
    )

    /**
     * KAN-68: sensible defaults zodat een nieuwe user direct relevante
     * events ontdekt zonder eerst handmatig de Settings-pagina te
     * moeten openen. Komt rechtstreeks uit de story (AC: "Sensible
     * defaults bij eerste aanmaak van een user").
     */
    internal val defaultEventPreferences = listOf(
        "JavaOne",
        "KotlinConf",
        "Spring I/O",
        "Code with Claude",
        "OpenAI DevDay",
        "Google I/O",
        "Devoxx",
        "KubeCon"
    )

    override fun getCategories(username: String): List<CategorySettings> {
        val list = categoryRepo.load(username)
        if (list.isEmpty()) {
            categoryRepo.save(username, defaultCategories)
            return defaultCategories
        }
        return ensureSystemCategories(list)
    }

    override fun saveCategories(username: String, categories: List<CategorySettings>): List<CategorySettings> {
        val withSystem = ensureSystemCategories(categories)
        categoryRepo.save(username, withSystem)
        return withSystem
    }

    override fun getRssFeeds(username: String): RssFeedsSettings = rssFeedsRepo.load(username)

    override fun saveRssFeeds(username: String, settings: RssFeedsSettings): RssFeedsSettings {
        settings.feeds.forEach { url ->
            val result = SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback)
            if (result is SsrfUrlValidator.ValidationResult.Invalid) {
                throw BadRequestException("Ongeldige RSS-feed-URL '$url': ${result.reason}")
            }
        }
        rssFeedsRepo.save(username, settings)
        return settings
    }

    override fun getPodcastFeeds(username: String): PodcastFeedsSettings = podcastFeedsRepo.load(username)

    override fun savePodcastFeeds(username: String, settings: PodcastFeedsSettings): PodcastFeedsSettings {
        settings.feeds.map { it.url }.forEach { url ->
            val result = SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback)
            if (result is SsrfUrlValidator.ValidationResult.Invalid) {
                throw BadRequestException("Ongeldige podcast-feed-URL '$url': ${result.reason}")
            }
        }
        podcastFeedsRepo.save(username, settings)
        return settings
    }

    override fun getEventPreferences(username: String): EventPreferences {
        val list = eventPreferencesRepo.load(username)
        if (list.isEmpty()) {
            eventPreferencesRepo.save(username, defaultEventPreferences)
            return EventPreferences(defaultEventPreferences)
        }
        return EventPreferences(list)
    }

    override fun saveEventPreferences(username: String, settings: EventPreferences): EventPreferences {
        // Trim + dedup + drop leeg. De Tavily-query plakt deze naam in
        // de search-string, dus willen we hier geen surrounding spaces.
        val clean = settings.names
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        eventPreferencesRepo.save(username, clean)
        return EventPreferences(clean)
    }

    override fun addEventPreference(username: String, name: String): EventPreferences {
        val clean = name.trim()
        if (clean.isNotBlank()) eventPreferencesRepo.add(username, clean)
        // Re-read zodat de UI meteen de actuele lijst krijgt
        // (inclusief de defaults als de lijst eerst leeg was).
        return getEventPreferences(username)
    }

    override fun removeEventPreference(username: String, name: String): EventPreferences {
        eventPreferencesRepo.remove(username, name.trim())
        return EventPreferences(eventPreferencesRepo.load(username))
    }

    override fun getEventDenylist(username: String): EventDenylist =
        EventDenylist(eventDenylistRepo.load(username))

    override fun addEventToDenylist(username: String, normalizedId: String, name: String): Boolean =
        eventDenylistRepo.add(username, normalizedId.trim(), name.trim())

    override fun removeEventFromDenylist(username: String, normalizedId: String): EventDenylist {
        eventDenylistRepo.remove(username, normalizedId.trim())
        return EventDenylist(eventDenylistRepo.load(username))
    }

    private fun ensureSystemCategories(list: List<CategorySettings>): List<CategorySettings> {
        val result = list.toMutableList()
        defaultCategories.filter { it.isSystem }.forEach { sysCat ->
            if (result.none { it.id == sysCat.id }) result.add(sysCat)
        }
        return result
    }
}
