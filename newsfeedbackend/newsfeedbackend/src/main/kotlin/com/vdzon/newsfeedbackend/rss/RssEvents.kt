package com.vdzon.newsfeedbackend.rss

/**
 * Publieke events van de rss-module. Op package-root zodat andere
 * modules (podcast_source, events) er via de module-API op mogen
 * reageren of ze mogen publiceren zonder in rss-internals te grijpen.
 */

/** Start een volledige refresh-run (fetch → samenvatten → selecteren). */
data class RssRefreshRequested(val username: String)

/** Draai alleen de AI-selectie opnieuw over al opgeslagen items. */
data class RssReselectRequested(val username: String)

/**
 * KAN-60: gepubliceerd (door podcast_source) zodra een podcast-aflevering
 * klaar is voor feed-promotie (transcript verwerkt óf 24h-timeout
 * verstreken). De refresh-pipeline draait dan de AI-selectie +
 * FeedItem-generatie voor die ene rss-rij.
 */
data class PodcastPromotionRequested(val username: String, val rssItemId: String)
