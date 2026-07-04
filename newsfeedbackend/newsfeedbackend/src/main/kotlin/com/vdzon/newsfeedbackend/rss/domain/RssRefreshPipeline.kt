package com.vdzon.newsfeedbackend.rss.domain

import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestService
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.rss.PodcastPromotionRequested
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.RssRefreshRequested
import com.vdzon.newsfeedbackend.rss.RssReselectRequested
import com.vdzon.newsfeedbackend.rss.infrastructure.RssFetcher
import com.vdzon.newsfeedbackend.rss.infrastructure.RssItemRepository
import com.vdzon.newsfeedbackend.settings.SettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Dunne orkestrator voor de uurlijkse RSS-verwerking: event-listeners,
 * locks/MDC, request-status-administratie en de run/reselect/promote-flows.
 * De AI-stappen zelf leven in [RssSummarizer], [FeedSelector] en
 * [FeedItemGenerator].
 */
@Component
class RssRefreshPipeline(
    private val rssRepo: RssItemRepository,
    private val fetcher: RssFetcher,
    private val settings: SettingsService,
    private val feed: FeedService,
    private val requests: RequestService,
    private val meters: MeterRegistry,
    private val topicHistory: TopicHistoryRepository,
    private val summarizer: RssSummarizer,
    private val selector: FeedSelector,
    private val feedItemGenerator: FeedItemGenerator
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    @EventListener
    @Async
    fun onRefresh(event: RssRefreshRequested) = run(event.username)

    @EventListener
    @Async
    fun onReselect(event: RssReselectRequested) = reselect(event.username)

    /**
     * KAN-60: trigger feed-promotie voor één specifiek rss_items-id
     * (typisch een podcast-aflevering die transcript-fase heeft afgerond
     * óf de 24h-show-notes-timeout heeft geraakt). Draait de bestaande
     * AI-selectie op precies dit item en genereert bij selectie een
     * FeedItem.
     */
    @EventListener
    @Async
    fun onPodcastPromotion(event: PodcastPromotionRequested) =
        promoteSingleItem(event.username, event.rssItemId)

    fun run(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[RSS] refresh already running for '{}'", username)
            return
        }
        MDC.put("username", username)
        try {
            log.info("[RSS] start uurlijkse verwerking voor gebruiker '{}'", username)
            val started = Instant.now()

            val requestId = "hourly-update-$username"
            requests.upsert(
                username,
                requests.get(username, requestId)
                    ?.copy(status = RequestStatus.PROCESSING, processingStartedAt = started)
                    ?: NewsRequest(
                        id = requestId,
                        subject = "Uurlijkse RSS-update",
                        status = RequestStatus.PROCESSING,
                        isHourlyUpdate = true,
                        processingStartedAt = started
                    )
            )

            val cats = settings.getCategories(username).filter { it.enabled || it.isSystem }
            val feedUrls = settings.getRssFeeds(username).feeds
            val existingItems = rssRepo.load(username)
            val existingUrls = existingItems.map { it.url }.toHashSet()

            log.info("[RSS] stap 1/4: {} feeds parallel ophalen voor '{}'", feedUrls.size, username)
            val fetched = feedUrls.parallelStream()
                .map { fetcher.fetch(it, username) }
                .toList()
                .flatten()
                .filter { it.url !in existingUrls }
                .distinctBy { it.url }

            log.info("[RSS] {} nieuwe artikelen voor '{}'", fetched.size, username)
            log.info("[RSS] stap 2/4: AI-samenvatting per artikel ({} stuks)", fetched.size)
            val processed = mutableListOf<RssItem>()
            fetched.forEachIndexed { idx, item ->
                val summarized = summarizer.summarize(username, item, cats)
                if (summarized != null) processed.add(summarized)
                if ((idx + 1) % 5 == 0 || idx + 1 == fetched.size) {
                    log.info("[RSS]   samengevat {}/{}", idx + 1, fetched.size)
                }
            }
            rssRepo.upsertAll(username, processed)

            log.info("[RSS] stap 3/4: AI-selectie voor de persoonlijke feed ({} kandidaten)", processed.size)
            val selectionResults = if (processed.isEmpty()) emptyMap()
            else selector.selectForFeed(username, processed, cats, existingItems)
            val selectedCount = selectionResults.values.count { it.inFeed }
            log.info("[RSS]   selectie: {} van {} artikelen geselecteerd", selectedCount, processed.size)
            val withSelection = processed.map { item ->
                val sel = selectionResults[item.id]
                when {
                    sel == null -> item.copy(
                        inFeed = false,
                        feedReason = "AI heeft dit artikel niet beoordeeld (parse-fout of overgeslagen — zie backend log)"
                    )
                    sel.inFeed -> item.copy(inFeed = true, feedReason = sel.reason.ifBlank { "Geselecteerd door AI" })
                    else -> item.copy(
                        inFeed = false,
                        feedReason = sel.reason.ifBlank { "Niet geselecteerd voor de persoonlijke feed" }
                    )
                }
            }
            rssRepo.upsertAll(username, withSelection)

            val toFeed = withSelection.filter { it.inFeed }
            log.info("[RSS] stap 4/4: uitgebreide feed-samenvattingen genereren ({} stuks)", toFeed.size)
            var feedCount = 0
            for ((idx, rss) in toFeed.withIndex()) {
                log.info("[RSS]   feed-item {}/{}: {}", idx + 1, toFeed.size, rss.title.take(80))
                val feedItem = feedItemGenerator.generateFeedItem(username, rss, cats)
                feed.save(username, feedItem)
                rssRepo.upsert(username, rss.copy(feedItemId = feedItem.id))
                feedCount++
            }

            updateTopicHistory(username, withSelection)
            meters.counter("newsfeed.rss.items.processed", "username", username).increment(processed.size.toDouble())
            meters.counter("newsfeed.rss.items.in.feed", "username", username).increment(feedCount.toDouble())
            meters.timer("newsfeed.rss.fetch.duration", "username", username)
                .record(Duration.between(started, Instant.now()))

            val took = Duration.between(started, Instant.now()).seconds.toInt()
            // Null-safe: de request-rij kan tijdens de run verwijderd zijn
            // (user-delete of account-cleanup) — dat mag de afronding van
            // een verder geslaagde run niet laten crashen.
            val doneRequest = requests.get(username, requestId)?.copy(
                status = RequestStatus.DONE,
                completedAt = Instant.now(),
                durationSeconds = took,
                newItemCount = processed.size
            )
            if (doneRequest != null) {
                requests.upsert(username, doneRequest)
            } else {
                log.warn("[RSS] request '{}' verdween tijdens de run — status niet bijgewerkt", requestId)
            }
            log.info("[RSS] klaar: {} nieuwe artikelen, {} in feed, duur {}s", processed.size, feedCount, took)
        } catch (e: Exception) {
            log.error("[RSS] verwerking mislukt voor gebruiker '{}': {}", username, e.message, e)
            val r = requests.get(username, "hourly-update-$username")
            if (r != null) requests.upsert(username, r.copy(status = RequestStatus.FAILED, completedAt = Instant.now()))
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    /**
     * Re-runs only the selection step on already-stored RssItems. Skips
     * fetching and summarising — the cheap path. Useful when the selection
     * prompt changed or Claude rejected everything in a previous run.
     *
     * Items that newly land in the feed get a real FeedItem generated.
     * Items that go from inFeed=true → false keep their existing FeedItem
     * (we don't auto-delete to avoid surprising the user).
     */
    fun reselect(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[RSS] reselect skipped — refresh already running for '{}'", username)
            return
        }
        MDC.put("username", username)
        try {
            log.info("[RSS] start reselect voor '{}'", username)
            val cats = settings.getCategories(username).filter { it.enabled || it.isSystem }
            val all = rssRepo.load(username).toMutableList()
            if (all.isEmpty()) {
                log.info("[RSS] reselect: geen items om te beoordelen")
                return
            }
            log.info("[RSS] reselect: {} items naar AI-selectie", all.size)
            val verdicts = selector.selectForFeed(username, all, cats, all)
            if (verdicts.isEmpty()) {
                log.warn("[RSS] reselect: AI gaf geen verdicts terug — bestaande inFeed/feedReason ongewijzigd. Check PNF_OPENAI_API_KEY of de selectie-prompt.")
                return
            }
            val newlySelectedIds = mutableListOf<String>()
            var updatedCount = 0
            for (i in all.indices) {
                val item = all[i]
                val v = verdicts[item.id] ?: continue // geen verdict voor dit id → laat staan
                val updated = if (v.inFeed) {
                    if (!item.inFeed) newlySelectedIds.add(item.id)
                    item.copy(inFeed = true, feedReason = v.reason.ifBlank { "Geselecteerd door AI" })
                } else {
                    item.copy(inFeed = false, feedReason = v.reason.ifBlank { "Niet geselecteerd voor de persoonlijke feed" })
                }
                all[i] = updated
                updatedCount++
            }
            rssRepo.save(username, all)
            log.info("[RSS] reselect klaar: AI gaf {} verdicts ({} items geüpdatet, {} nieuw in feed)",
                verdicts.size, updatedCount, newlySelectedIds.size)

            // Generate FeedItem for newly-selected items only.
            for ((idx, id) in newlySelectedIds.withIndex()) {
                val rss = all.find { it.id == id } ?: continue
                if (rss.feedItemId != null) continue // already has one
                log.info("[RSS] reselect feed-item {}/{}: {}", idx + 1, newlySelectedIds.size, rss.title.take(80))
                val feedItem = feedItemGenerator.generateFeedItem(username, rss, cats)
                feed.save(username, feedItem)
                rssRepo.upsert(username, rss.copy(feedItemId = feedItem.id))
            }
        } catch (e: Exception) {
            log.error("[RSS] reselect mislukt voor '{}': {}", username, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    /**
     * KAN-60: feed-promotie voor één rss_items-rij. Wordt aangeroepen
     * vanuit de podcast-transcript-worker zodra een aflevering klaar is
     * (echte transcript-summary of 24h-show-notes-timeout).
     *
     * - Skip als het item niet bestaat of al een feed_item heeft
     *   (dubbele promotie voorkomen).
     * - Roept Claude aan via `selectForFeed` met deze ene rss-rij plus
     *   bestaande items als context (zodat de AI ranking-history mee
     *   weegt). Op een afwijzing wordt geen FeedItem aangemaakt, maar
     *   wel `inFeed=false` + reason op de rss-rij geschreven.
     * - Op selectie: genereert het FeedItem en koppelt 'm via
     *   `feed_item_id` op rss_items.
     */
    fun promoteSingleItem(username: String, rssItemId: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        // Wachten (niet droppen) bij contentie: als twee afleveringen
        // tegelijk klaar zijn, of er net een refresh draait, zou een
        // tryLock-skip deze promotie PERMANENT laten vervallen — het
        // event wordt nooit opnieuw gepubliceerd. Gevonden door
        // PodcastIngestE2eTest (2 afleveringen → 1 kwam nooit in de feed).
        if (!lock.tryLock(10, java.util.concurrent.TimeUnit.MINUTES)) {
            log.warn("[RSS] podcast-promotie voor rssItemId={} opgegeven — lock voor '{}' bleef >10m bezet",
                rssItemId, username)
            return
        }
        MDC.put("username", username)
        try {
            val all = rssRepo.load(username)
            val item = all.find { it.id == rssItemId } ?: run {
                log.warn("[RSS] podcast-promotie: rssItemId={} niet gevonden voor '{}'",
                    rssItemId, username)
                return
            }
            if (item.feedItemId != null) {
                log.debug("[RSS] podcast-promotie: rssItemId={} al gepromoot (feedItemId={})",
                    rssItemId, item.feedItemId)
                return
            }
            log.info("[RSS] podcast-promotie start voor '{}' — rssItemId={} title='{}'",
                username, rssItemId, item.title.take(80))
            val cats = settings.getCategories(username).filter { it.enabled || it.isSystem }
            val verdicts = selector.selectForFeed(username, listOf(item), cats, all)
            val verdict = verdicts[item.id]
            val updated = when {
                verdict == null -> item.copy(
                    inFeed = false,
                    feedReason = "AI heeft de podcast niet beoordeeld (parse-fout — zie backend log)"
                )
                verdict.inFeed -> item.copy(
                    inFeed = true,
                    feedReason = verdict.reason.ifBlank { "Geselecteerd door AI" }
                )
                else -> item.copy(
                    inFeed = false,
                    feedReason = verdict.reason.ifBlank { "Niet geselecteerd voor de persoonlijke feed" }
                )
            }
            rssRepo.upsert(username, updated)
            if (verdict?.inFeed == true) {
                val feedItem = feedItemGenerator.generateFeedItem(username, updated, cats)
                feed.save(username, feedItem)
                rssRepo.upsert(username, updated.copy(feedItemId = feedItem.id))
                log.info("[RSS] podcast-promotie klaar — rssItemId={} → feedItemId={}",
                    rssItemId, feedItem.id)
            } else {
                log.info("[RSS] podcast-promotie: AI wees rssItemId={} af — geen FeedItem", rssItemId)
            }
        } catch (e: Exception) {
            log.error("[RSS] podcast-promotie mislukt voor '{}' rssItemId={}: {}",
                username, rssItemId, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun updateTopicHistory(username: String, items: List<RssItem>) {
        topicHistory.update(username) { entries ->
            val now = Instant.now().toString()
            for (item in items) {
                for (topic in item.topics) {
                    val idx = entries.indexOfFirst { it.topic.equals(topic, ignoreCase = true) }
                    if (idx >= 0) {
                        entries[idx] = entries[idx].copy(
                            lastSeenNews = now,
                            newsCount = entries[idx].newsCount + 1
                        )
                    } else {
                        entries.add(TopicEntry(topic = topic, firstSeen = now, lastSeenNews = now, newsCount = 1))
                    }
                }
            }
        }
    }

}
