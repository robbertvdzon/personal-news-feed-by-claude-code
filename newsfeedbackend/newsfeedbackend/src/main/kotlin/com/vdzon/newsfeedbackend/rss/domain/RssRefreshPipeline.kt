package com.vdzon.newsfeedbackend.rss.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestService
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.rss.RssItem
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class RssRefreshPipeline(
    private val rssRepo: RssItemRepository,
    private val fetcher: RssFetcher,
    private val anthropic: AnthropicClient,
    private val settings: SettingsService,
    private val feed: FeedService,
    private val requests: RequestService,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry,
    private val topicHistory: TopicHistoryRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    @EventListener
    @Async
    fun onRefresh(event: RssRefreshRequested) = run(event.username)

    fun run(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[RSS] refresh already running for '{}'", username)
            return
        }
        MDC.put("username", username)
        try {
            log.info("[RSS] start dagelijkse verwerking voor gebruiker '{}'", username)
            val started = Instant.now()

            val requestId = "daily-update-$username"
            requests.upsert(
                username,
                requests.get(username, requestId)
                    ?.copy(status = RequestStatus.PROCESSING, processingStartedAt = started)
                    ?: NewsRequest(
                        id = requestId,
                        subject = "Dagelijkse update",
                        status = RequestStatus.PROCESSING,
                        isDailyUpdate = true,
                        processingStartedAt = started
                    )
            )

            val cats = settings.getCategories(username).filter { it.enabled || it.isSystem }
            val feedUrls = settings.getRssFeeds(username).feeds
            val existingItems = rssRepo.load(username)
            val existingUrls = existingItems.map { it.url }.toHashSet()

            val fetched = feedUrls.parallelStream()
                .map { fetcher.fetch(it) }
                .toList()
                .flatten()
                .filter { it.url !in existingUrls }
                .distinctBy { it.url }

            log.info("[RSS] {} nieuwe artikelen voor '{}'", fetched.size, username)
            val processed = fetched.mapNotNull { summarize(username, it, cats.map { c -> c.id to c.name }) }
            rssRepo.upsertAll(username, processed)

            val selected = if (processed.isEmpty()) emptyList() else selectForFeed(username, processed, cats.map { c -> c.id to c.name })
            val withSelection = processed.map {
                val sel = selected.find { s -> s.first == it.id }
                if (sel != null) it.copy(inFeed = true, feedReason = sel.second) else it
            }
            rssRepo.upsertAll(username, withSelection)

            var feedCount = 0
            for (rss in withSelection.filter { it.inFeed }) {
                val ai = anthropic.complete(
                    operation = "generateFeedItemSummary",
                    system = "Je schrijft een uitgebreide journalistieke samenvatting van 400-600 woorden in het Nederlands.",
                    user = "Titel: ${rss.title}\nURL: ${rss.url}\n\nBron-tekst:\n${rss.snippet}"
                )
                val feedItem = FeedItem(
                    id = UUID.randomUUID().toString(),
                    title = rss.title,
                    summary = ai.text,
                    url = rss.url,
                    category = rss.category,
                    source = rss.source,
                    sourceRssIds = listOf(rss.id),
                    sourceUrls = listOf(rss.url),
                    topics = rss.topics,
                    feedReason = rss.feedReason,
                    publishedDate = rss.publishedDate,
                    createdAt = Instant.now()
                )
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
            requests.upsert(
                username,
                requests.get(username, requestId)!!.copy(
                    status = RequestStatus.DONE,
                    completedAt = Instant.now(),
                    durationSeconds = took,
                    newItemCount = processed.size
                )
            )
            log.info("[RSS] klaar: {} nieuwe artikelen, {} in feed, duur {}s", processed.size, feedCount, took)
        } catch (e: Exception) {
            log.error("[RSS] verwerking mislukt voor gebruiker '{}': {}", username, e.message, e)
            val r = requests.get(username, "daily-update-$username")
            if (r != null) requests.upsert(username, r.copy(status = RequestStatus.FAILED, completedAt = Instant.now()))
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun summarize(username: String, rss: RssItem, categories: List<Pair<String, String>>): RssItem? {
        val catList = categories.joinToString("\n") { "- ${it.first}: ${it.second}" }
        val ai = anthropic.complete(
            operation = "summarizeRssItem",
            model = anthropic.summaryModel(),
            system = "Je vat artikelen kort samen (150-250 woorden) in het Nederlands. Wijs een categorie-id toe en extraheer 2-3 onderwerpen.",
            user = """
                Beschikbare categorieën:
                $catList

                Artikel:
                Titel: ${rss.title}
                Snippet: ${rss.snippet}

                Antwoord uitsluitend in JSON met velden: {"summary": "...", "category": "kotlin", "topics": ["...","..."]}
            """.trimIndent()
        )
        return try {
            val tree = mapper.readTree(extractJson(ai.text))
            rss.copy(
                summary = tree.path("summary").asText(""),
                category = tree.path("category").asText("overig").ifBlank { "overig" },
                topics = tree.path("topics").map { it.asText() },
                processedAt = Instant.now()
            )
        } catch (e: Exception) {
            log.warn("[RSS] kon AI samenvatting niet parsen voor '{}': {}", rss.title, e.message)
            rss.copy(summary = ai.text, processedAt = Instant.now())
        }
    }

    private fun selectForFeed(username: String, items: List<RssItem>, categories: List<Pair<String, String>>): List<Pair<String, String>> {
        val titles = items.joinToString("\n") { "${it.id}|${it.category}|${it.title}" }
        val ai = anthropic.complete(
            operation = "selectFeedItems",
            system = "Je selecteert artikelen voor een persoonlijke feed. Geen vaste minimum of maximum.",
            user = """
                Artikelen (id|category|title):
                $titles

                Antwoord uitsluitend met JSON-array: [{"id": "...", "inFeed": true, "reason": "..."}]
                Includeer alleen artikelen die echt interessant zijn.
            """.trimIndent()
        )
        return try {
            val tree = mapper.readTree(extractJson(ai.text))
            tree.filter { it.path("inFeed").asBoolean(false) }
                .map { it.path("id").asText() to it.path("reason").asText("") }
        } catch (e: Exception) {
            log.warn("[RSS] selectie parse fout: {}", e.message)
            emptyList()
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

    private fun extractJson(text: String): String {
        val start = text.indexOf('{').let { o -> if (o < 0) text.indexOf('[') else o }
        val end = maxOf(text.lastIndexOf('}'), text.lastIndexOf(']'))
        if (start < 0 || end < 0 || end <= start) return text
        return text.substring(start, end + 1)
    }
}
