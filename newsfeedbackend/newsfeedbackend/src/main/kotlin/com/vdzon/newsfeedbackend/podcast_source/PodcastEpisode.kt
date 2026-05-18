package com.vdzon.newsfeedbackend.podcast_source

import java.time.Instant

/**
 * Tracking-rij voor één podcast-aflevering tijdens de tweefasen-pipeline.
 * PK (username, guid) is meteen de idempotency-cache: een refresh die
 * dezelfde GUID opnieuw tegenkomt verwerkt 'm niet opnieuw.
 *
 * KAN-60-statusverloop:
 *   PENDING
 *     → SUMMARIZING_FROM_NOTES   (snelle fase, op kritieke pad)
 *     → NEEDS_TRANSCRIPT          (card staat al online, transcript volgt)
 *         ↳ DOWNLOADING → TRANSCRIBING → SUMMARIZING
 *         → DONE                  (transcript-based summary, badge weg)
 *     → SHOW_NOTES_DONE           (terminale state als transcribeEnabled=false)
 *   FAILED                        (alleen voor fatale fouten, niet voor
 *                                  429/5xx — die worden geretryd)
 */
data class PodcastEpisode(
    val username: String,
    val guid: String,
    val feedUrl: String,
    val podcastName: String = "",
    val title: String = "",
    val audioUrl: String = "",
    val durationSeconds: Int? = null,
    val publishedDate: String? = null,
    val showNotes: String = "",
    val transcript: String = "",
    val summary: String = "",
    val status: PodcastEpisodeStatus = PodcastEpisodeStatus.PENDING,
    val errorMessage: String? = null,
    val rssItemId: String? = null,
    /** KAN-60: aantal mislukte Whisper-pogingen (429/5xx). Stuurt de backoff. */
    val retryCount: Int = 0,
    /**
     * KAN-60: wanneer de [PodcastTranscriptWorker] op z'n vroegst opnieuw
     * mag proberen. `null` = direct opneembaar. Wordt door de worker
     * gezet bij 429/5xx; volgt de tabel 5m → 15m → 45m → 24h.
     */
    val nextAttemptAt: Instant? = null,
    /**
     * KAN-60: 'show_notes' zolang de samenvatting nog op de RSS-description
     * gebaseerd is; 'transcript' zodra Whisper+Claude opnieuw heeft
     * gedraaid. De frontend gebruikt dit (op rss_items) om de
     * voorlopige-badge te tonen.
     */
    val summarySource: String = "transcript",
    /**
     * KAN-62: lange Nederlandse samenvatting voor het podcast-detail-
     * scherm (3-5 alinea's, ~400-600 woorden). `null` voor cards die
     * nog niet door de uitgebreide Claude-prompt gegaan zijn — de
     * frontend valt in dat geval terug op de short-summary in
     * [summary]. Wordt gevuld door [PodcastEpisodeProcessor.summarize]
     * (nieuwe afleveringen) en door [PodcastBackfillRunner] (de
     * 14 bestaande KAN-60-rijen).
     */
    val longSummary: String? = null,
    /**
     * KAN-62: 5-10 concrete takeaway-bullets uit de aflevering. Eén
     * regel per bullet, geen sub-bullets, geen markdown-headers.
     * `null` of leeg = sectie wordt verborgen op het detail-scherm.
     */
    val keyTakeaways: List<String> = emptyList(),
    /**
     * KAN-60 (V8): wanneer de show-notes-timeout-promotie 1x getriggerd
     * is voor deze aflevering. Voorkomt dat de worker elke tick opnieuw
     * een Claude-selectie-call afvuurt voor een door AI afgewezen item
     * (waar `rss_items.feed_item_id` NULL blijft). `null` = nog niet
     * geprobeerd.
     */
    val feedPromotionAttemptedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class PodcastEpisodeStatus {
    PENDING,
    SUMMARIZING_FROM_NOTES,
    NEEDS_TRANSCRIPT,
    SHOW_NOTES_DONE,
    DOWNLOADING,
    TRANSCRIBING,
    SUMMARIZING,
    DONE,
    FAILED
}
