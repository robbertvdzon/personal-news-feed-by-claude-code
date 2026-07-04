package com.vdzon.newsfeedbackend.podcast_source.domain

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.rss.RssService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * KAN-62: retroactief de longSummary + keyTakeaways voor bestaande
 * DONE-afleveringen vullen. Wordt aangedreven door
 * [com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastBackfillRunner].
 */
@Component
class PodcastLongSummaryBackfiller(
    private val episodeRepo: PodcastEpisodeRepository,
    private val rssItems: RssService,
    private val summarizer: PodcastEpisodeSummarizer
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * KAN-62: retroactief de longSummary + keyTakeaways voor één
     * bestaande DONE-aflevering vullen. Gebruikt het al opgeslagen
     * `transcript` als input — geen Whisper-call. Update zowel de
     * podcast_episodes-rij als de gekoppelde rss_items-rij zodat het
     * detail-scherm de nieuwe velden direct ziet.
     *
     * Retourneert true als de backfill geslaagd is (of niets te doen),
     * false bij een Claude-fout (zodat de runner z'n teller kan loggen
     * en eventueel later opnieuw kan proberen).
     */
    fun backfillLongSummary(ep: PodcastEpisode): Boolean {
        if (ep.transcript.isBlank()) {
            log.debug("[PodcastEpisode-backfill] guid={} heeft geen transcript — overgeslagen", ep.guid)
            return true
        }
        if (!ep.longSummary.isNullOrBlank()) {
            log.debug("[PodcastEpisode-backfill] guid={} heeft al een longSummary — overgeslagen", ep.guid)
            return true
        }
        return try {
            MDC.put("username", ep.username)
            val summarized = summarizer.summarize(ep.username, ep, ep.transcript)
            if (summarized == null) {
                log.warn("[PodcastEpisode-backfill] Claude faalde op transcript voor guid={} — laat rij ongewijzigd",
                    ep.guid)
                return false
            }

            val rssItemId = ep.rssItemId
            if (rssItemId != null) {
                val existing = rssItems.get(ep.username, rssItemId)
                if (existing != null) {
                    rssItems.upsert(ep.username, existing.copy(
                        // shortSummary blijft staan om scroll-flicker te
                        // voorkomen (de oude is meestal vergelijkbaar). De
                        // toegevoegde long-velden zijn waar de story om
                        // vraagt.
                        longSummary = summarized.longSummary.ifBlank { null },
                        keyTakeaways = summarized.keyTakeaways,
                        // Topics + category eventueel ook bijwerken — de
                        // nieuwe prompt geeft consistentere topics.
                        topics = summarized.topics.ifEmpty { existing.topics },
                        category = summarized.category.ifBlank { existing.category }
                    ))
                } else {
                    log.warn("[PodcastEpisode-backfill] rss_items.id={} ontbreekt voor guid={} — alleen podcast_episodes geüpdatet",
                        rssItemId, ep.guid)
                }
            }

            episodeRepo.upsert(ep.copy(
                longSummary = summarized.longSummary.ifBlank { null },
                keyTakeaways = summarized.keyTakeaways
            ))
            log.info("[PodcastEpisode-backfill] guid={} verrijkt: longSummary={} chars, {} takeaways",
                ep.guid, summarized.longSummary.length, summarized.keyTakeaways.size)
            true
        } catch (e: Exception) {
            log.warn("[PodcastEpisode-backfill] onverwachte fout voor guid={}: {}", ep.guid, e.message)
            false
        } finally {
            MDC.clear()
        }
    }
}
