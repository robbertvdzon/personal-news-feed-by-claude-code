package com.vdzon.newsfeedbackend.events

import java.time.Instant

/**
 * KAN-65: publieke interface van de events-module.
 *
 * Events zijn grote, relevante tech-conferenties (JavaOne, KotlinConf,
 * Spring I/O, Devoxx, KubeCon, Google I/O, OpenAI DevDay e.d.) die
 * wekelijks per gebruiker met AI + web-search worden ontdekt op basis
 * van de categorie-settings. Opslag is per-gebruiker (username als deel
 * van de PK), consistent met rss_items en feed_items.
 */
interface EventService {
    fun list(username: String): List<Event>
    fun get(username: String, id: String): Event?
    fun delete(username: String, id: String): Boolean

    /**
     * Start asynchroon de AI-ontdekking van tech-events voor deze
     * gebruiker. Mirror van [com.vdzon.newsfeedbackend.rss.RssService.triggerRefresh]:
     * publiceert een event dat de [com.vdzon.newsfeedbackend.events.domain.EventDiscoveryPipeline]
     * @Async oppakt. De call komt direct terug.
     */
    fun triggerDiscovery(username: String)
}

/**
 * Eén ontdekt tech-event.
 *
 * Dedup-sleutel is [id]: een stabiele genormaliseerde identiteit
 * (naam + jaar, bijv. `javaone-2026`). Een tweede ontdekking van
 * hetzelfde event werkt de bestaande rij bij i.p.v. te dupliceren.
 */
data class Event(
    /** Stabiele identiteit: genormaliseerde naam + jaar, bv. "javaone-2026". */
    val id: String,
    val name: String,
    /** Organiserende partij. Optioneel — AI laat 'm leeg waar onbekend. */
    val organization: String? = null,
    /** Begindatum YYYY-MM-DD. Null wanneer AI geen datum kon vaststellen. */
    val startDate: String? = null,
    /** Einddatum YYYY-MM-DD. Null voor één-dag-events of bij onbekend. */
    val endDate: String? = null,
    /** Locatie (stad/land/online). Leeg wanneer onbekend. */
    val location: String = "",
    /** Nederlandse beschrijving van het event en de onderwerpen. */
    val description: String = "",
    /** Bron-links waar de info vandaan komt. */
    val sourceLinks: List<String> = emptyList(),
    /** Categorie-id waaronder dit event ontdekt is. */
    val category: String = "overig",
    /** Gekoppeld aankondigings-FeedItem (gezet bij eerste ontdekking). */
    val feedItemId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
