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

    /** KAN-66: de ontdekte video's van één event, nieuwste eerst. */
    fun listVideos(username: String, eventId: String): List<EventVideo>

    /**
     * KAN-66: start asynchroon de AI-ontdekking van video's per event voor
     * deze gebruiker. Aparte job van [triggerDiscovery]: publiceert een
     * event dat de [com.vdzon.newsfeedbackend.events.domain.EventVideoDiscoveryPipeline]
     * @Async oppakt. De call komt direct terug.
     */
    fun triggerVideoDiscovery(username: String)

    /**
     * KAN-67: maak (of geef bestaande) Nederlandse samenvatting van één
     * video op aanvraag. Synchroon: de frontend toont een laad-indicator
     * tot deze call terugkomt. Idempotent — een tweede call op dezelfde
     * video met een al bestaande samenvatting doet geen AI-calls en geeft
     * direct de opgeslagen tekst terug.
     *
     * Returnt de bijgewerkte [EventVideo]. Bij `null` heeft de transcript-
     * fase niets opgeleverd (YouTube zonder ondertiteling én Whisper
     * faalde) — caller mag een 502/error-response sturen.
     */
    fun ensureVideoSummary(username: String, eventId: String, videoUrl: String): EventVideo?
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

/**
 * KAN-66: één online video (keynote/sessie) van een [Event].
 *
 * Wekelijks ontdekt met AI + web-search. Dedup-sleutel is de canonieke
 * [videoUrl] per (gebruiker, event); een tweede ontdekking van dezelfde
 * video werkt de bestaande rij bij i.p.v. te dupliceren.
 *
 * KAN-67: voegt [summaryNl] toe — een on-demand Nederlandse samenvatting
 * van de video-inhoud (op basis van transcript). Default null; wordt pas
 * gevuld wanneer de gebruiker er expliciet om vraagt. De wekelijkse
 * discovery-upsert (zie [com.vdzon.newsfeedbackend.events.infrastructure.EventVideoRepository])
 * raakt dit veld bewust niet aan.
 */
data class EventVideo(
    /** Id van het event waar deze video bij hoort. */
    val eventId: String,
    /** Canonieke video-URL — tevens de dedup-sleutel. */
    val videoUrl: String,
    val title: String,
    /** Nederlandse beschrijving van waar de video over gaat. Null wanneer onbekend. */
    val descriptionNl: String? = null,
    /**
     * KAN-67: Nederlandse on-demand samenvatting van de video-inhoud
     * (Claude op basis van YouTube- of Whisper-transcript). Null tot de
     * gebruiker op "Maak samenvatting" drukt. Wordt nooit overschreven
     * door de wekelijkse discovery.
     */
    val summaryNl: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
