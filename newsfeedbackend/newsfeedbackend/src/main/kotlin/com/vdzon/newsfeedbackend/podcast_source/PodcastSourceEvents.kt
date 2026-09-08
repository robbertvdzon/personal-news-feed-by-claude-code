package com.vdzon.newsfeedbackend.podcast_source

/**
 * Publieke applicatie-events van de podcast_source-module (Spring
 * Modulith: events horen op moduleniveau, niet in `domain`/
 * `infrastructure`, zodat andere modules ze mogen zien — zelfde
 * plaatsing als `rss/RssEvents.kt`).
 */

/**
 * SF-1739: gepubliceerd zodra een aflevering transcriptwerk nodig
 * krijgt (de overgang naar `NEEDS_TRANSCRIPT` in
 * [com.vdzon.newsfeedbackend.podcast_source.domain.PodcastShowNotesProcessor]),
 * en door de uurlijkse recovery-job voor afleveringen die alsnog aan
 * de beurt zijn.
 *
 * Vervangt de oude 2-minuten-`@Scheduled`-poll: de transcript-fase
 * start nu meldingsgestuurd, zodat de database in rust echt met rust
 * gelaten wordt (Neon scale-to-zero).
 */
data class PodcastTranscriptRequested(val username: String, val guid: String)
