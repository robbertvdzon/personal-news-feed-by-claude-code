package com.vdzon.newsfeedbackend.podcast_source

/**
 * Cross-module hook: laat de RSS-refresh-pipeline (die niets weet van
 * podcast-tabellen) toch het volledige transcript ophalen voor een
 * gepromoveerd podcast-rss_item, zodat de uitgebreide Feed-tab-
 * samenvatting niet hoeft terug te vallen op alleen de show-notes.
 *
 * Returnt `null` als er geen transcript is (b.v. transcribe-toggle
 * stond uit, of het is een gewoon artikel-rss_item).
 */
interface PodcastTranscriptLookup {
    fun findTranscriptForRssItem(username: String, rssItemId: String): String?
}
