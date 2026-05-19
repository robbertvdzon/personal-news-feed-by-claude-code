package com.vdzon.newsfeedbackend.podcast

/**
 * KAN-63: vertaal-knop op het RSS-podcast-detail-scherm.
 *
 * - [lookup] wordt door de Flutter-app aangeroepen wanneer de
 *   detail-pagina opent en bepaalt of de knop "Vertaal & genereer" of
 *   "Bekijk vertaling" moet tonen, met een transcript-char-count voor
 *   de kosten-schatting.
 * - [startTranslation] start de async vertaling voor één aflevering en
 *   is idempotent: bestaat er al een vertaling, dan returnt 'ie de
 *   bestaande podcast-id (HTTP 200) i.p.v. een nieuwe (HTTP 202).
 */
interface PodcastTranslationService {

    fun lookup(username: String, rssItemId: String): EpisodeLookup?

    fun startTranslation(username: String, episodeGuid: String): TranslationStart
}

/**
 * Het resultaat dat de translate-knop op de RSS-podcast-detail-pagina
 * gebruikt. `episodeGuid` is de sleutel voor de POST-call,
 * `transcriptCharCount` de input voor de cost-schatting in de UI.
 * `translatedPodcastId`/`translatedPodcastStatus` zijn null wanneer er
 * nog geen vertaal-poging gedaan is.
 */
data class EpisodeLookup(
    val episodeGuid: String,
    val episodeTitle: String,
    val episodeStatus: String,
    val transcriptCharCount: Int,
    val feedUrl: String,
    val feedName: String,
    val rssItemId: String?,
    val translatedPodcastId: String? = null,
    val translatedPodcastStatus: String? = null,
    val translatedPodcastTitle: String? = null,
    val translatedPodcastErrorMessage: String? = null
)

/**
 * Resultaat van een POST naar de translate-endpoint.
 *
 * - `created=true`  → nieuwe vertaal-job gestart, HTTP 202
 * - `created=false` → er bestond al een vertaling (DONE of in progress),
 *                     bestaande podcast-id wordt geretourneerd, HTTP 200
 */
data class TranslationStart(
    val podcastId: String,
    val status: String,
    val created: Boolean
)
