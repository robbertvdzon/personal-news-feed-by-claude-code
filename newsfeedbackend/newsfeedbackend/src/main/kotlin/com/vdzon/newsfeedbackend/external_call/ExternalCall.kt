package com.vdzon.newsfeedbackend.external_call

import java.time.Instant

/**
 * Eén log-regel voor een call naar een externe partij. Wordt geappend aan
 * `data/external_calls.jsonl` (één JSON-object per regel).
 *
 * Het idee: "elke keer als we een externe API aanroepen schrijven we een
 * regel met provider, actie, kosten, duur, units, gebruiker en status".
 * Dit is dus géén pure cost-log maar een audit-log waarin de kosten
 * toevallig ook zitten — vandaar de naam.
 */
data class ExternalCall(
    val id: String,
    val provider: String,
    val action: String,
    val username: String,
    val startTime: Instant,
    val endTime: Instant,
    val durationMs: Long,
    /** Voor LLM-providers (anthropic): aantal input-tokens. Anders null. */
    val tokensIn: Long? = null,
    /** Voor LLM-providers (anthropic): aantal output-tokens. Anders null. */
    val tokensOut: Long? = null,
    /** Catch-all veld: voor TTS = #characters, voor Tavily = 1 (per call). */
    val units: Long? = null,
    /** "tokens" | "characters" | "queries" */
    val unitType: String,
    val costUsd: Double,
    /** "ok" | "error" */
    val status: String,
    /** Bij status=error: korte omschrijving. */
    val errorMessage: String? = null,
    /** Vrije context: bv. artikeltitel, podcast-id. Niet gequeried. */
    val subject: String? = null
) {
    companion object {
        // Provider-constants
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_ELEVENLABS = "elevenlabs"
        const val PROVIDER_TAVILY = "tavily"
        // Gratis externe partijen — geen kosten, wel zinvol voor audit/diagnose:
        const val PROVIDER_RSS = "rss"   // RSS-feed XML ophalen
        const val PROVIDER_WEB = "web"   // Volledige artikel-HTML scrapen

        // Action-constants
        const val ACTION_RSS_SUMMARIZE = "rss_summarize"
        const val ACTION_FEED_SUMMARIZE = "feed_summarize"
        const val ACTION_FEED_SCORE = "feed_score"
        const val ACTION_DAILY_SUMMARY = "daily_summary"
        const val ACTION_PODCAST_TOPICS = "podcast_topics"
        const val ACTION_PODCAST_SCRIPT = "podcast_script"
        const val ACTION_PODCAST_TTS = "podcast_tts"
        const val ACTION_TAVILY_SEARCH = "tavily_search"
        const val ACTION_TAVILY_EXTRACT = "tavily_extract"
        const val ACTION_ADHOC_SUMMARIZE = "adhoc_summarize"
        const val ACTION_RSS_FETCH = "rss_fetch"
        const val ACTION_ARTICLE_FETCH = "article_fetch"

        // UnitType-constants
        const val UNIT_TOKENS = "tokens"
        const val UNIT_CHARACTERS = "characters"
        const val UNIT_QUERIES = "queries"
        /** items teruggekregen uit een RSS-feed — handig om te zien hoe productief een feed is. */
        const val UNIT_ITEMS = "items"
    }
}
