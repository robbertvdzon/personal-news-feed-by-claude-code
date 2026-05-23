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

        // KAN-56: podcast-bron-ingestie.
        const val ACTION_PODCAST_FEED_FETCH = "podcast_feed_fetch"
        const val ACTION_PODCAST_AUDIO_DOWNLOAD = "podcast_audio_download"
        const val ACTION_PODCAST_TRANSCRIBE = "podcast_transcribe"
        const val ACTION_PODCAST_EPISODE_SUMMARIZE = "podcast_episode_summarize"

        // KAN-63: vertaling van een RSS-podcast-aflevering naar een NL audio-podcast.
        const val ACTION_PODCAST_TRANSLATE = "podcast_translate"
        const val ACTION_PODCAST_TRANSLATE_TTS = "podcast_translate_tts"

        // KAN-65: wekelijkse AI-ontdekking van tech-events per gebruiker.
        const val ACTION_EVENT_DISCOVERY = "event_discovery"

        // KAN-66: wekelijkse AI-ontdekking van video's (keynotes/sessies) per event.
        const val ACTION_EVENT_VIDEO_DISCOVERY = "event_video_discovery"

        // KAN-67: on-demand Nederlandse samenvatting van één event-video.
        const val ACTION_EVENT_VIDEO_SUMMARIZE = "event_video_summarize"
        /** KAN-67: yt-dlp audio-download voor de Whisper-fallback. */
        const val ACTION_EVENT_VIDEO_AUDIO_DOWNLOAD = "event_video_audio_download"
        /** KAN-67: YouTube timedtext-fetch voor de transcript-stap. */
        const val ACTION_EVENT_VIDEO_TRANSCRIPT_FETCH = "event_video_transcript_fetch"

        // UnitType-constants
        const val UNIT_TOKENS = "tokens"
        const val UNIT_CHARACTERS = "characters"
        const val UNIT_QUERIES = "queries"
        /** items teruggekregen uit een RSS-feed — handig om te zien hoe productief een feed is. */
        const val UNIT_ITEMS = "items"
        /** seconden audio — Whisper-prijs is per minuut. */
        const val UNIT_SECONDS = "seconds"
        /** bytes — voor audio-download van podcast-MP3's. */
        const val UNIT_BYTES = "bytes"
    }
}
