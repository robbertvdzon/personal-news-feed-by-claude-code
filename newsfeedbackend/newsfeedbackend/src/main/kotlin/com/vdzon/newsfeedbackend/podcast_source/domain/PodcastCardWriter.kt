package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.rss.PodcastPromotionRequested
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.RssService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * KAN-60: schrijft/actualiseert het rss_items-kaartje voor een
 * podcast-aflevering en triggert de feed-promotie.
 *
 * Gedeeld door beide pipeline-fasen: fase 1 schrijft de card met
 * `summary_source='show_notes'` (voorlopige badge in de UI), fase 2
 * overschrijft 'm met `summary_source='transcript'` (badge verdwijnt).
 */
@Component
class PodcastCardWriter(
    private val rssItems: RssService,
    private val events: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Schrijft (of update) de rss-card voor deze aflevering met de
     * gegeven samenvatting.
     */
    internal fun upsertCard(
        username: String,
        ep: PodcastEpisode,
        sum: PodcastEpisodeSummarizer.Summarized,
        rssItemId: String,
        summarySource: String
    ) {
        val rss = buildRssItem(ep, sum, rssItemId, summarySource)
        rssItems.upsert(username, rss)
    }

    fun triggerFeedPromotion(username: String, rssItemId: String) {
        try {
            events.publishEvent(PodcastPromotionRequested(username = username, rssItemId = rssItemId))
        } catch (e: Exception) {
            log.warn("[PodcastEpisode] kon promotion-event niet publiceren voor rssItemId={}: {}",
                rssItemId, e.message)
        }
    }

    private fun buildRssItem(
        ep: PodcastEpisode,
        sum: PodcastEpisodeSummarizer.Summarized,
        rssItemId: String,
        summarySource: String
    ): RssItem {
        // Bij re-runs hergebruiken we het oude rss_items.id zodat
        // gebruikersinteractie (sterren, isRead) bewaard blijft.
        val existing = ep.rssItemId?.let { rid ->
            rssItems.get(ep.username, rid)
        }
        return (existing ?: RssItem(
            id = rssItemId,
            title = ep.title,
            url = ep.audioUrl.ifBlank { ep.feedUrl },
            feedUrl = ep.feedUrl,
            source = ep.podcastName,
            timestamp = Instant.now(),
            mediaType = "PODCAST",
            audioUrl = ep.audioUrl,
            durationSeconds = ep.durationSeconds,
            summarySource = summarySource
        )).copy(
            title = ep.title,
            summary = sum.shortSummary,
            url = ep.audioUrl.ifBlank { ep.feedUrl },
            feedUrl = ep.feedUrl,
            source = ep.podcastName,
            snippet = ep.showNotes.take(1000),
            publishedDate = ep.publishedDate,
            category = sum.category,
            topics = sum.topics,
            mediaType = "PODCAST",
            audioUrl = ep.audioUrl,
            durationSeconds = ep.durationSeconds,
            summarySource = summarySource,
            // KAN-62: schrijf longSummary alleen als Claude er één gaf —
            // anders blijft een eerdere waarde staan (b.v. wanneer de
            // transcript-fase mislukt en we terugvallen op de oude
            // show-notes-card).
            longSummary = sum.longSummary.ifBlank { null },
            keyTakeaways = sum.keyTakeaways,
            processedAt = Instant.now()
        )
    }
}
