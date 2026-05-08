package com.vdzon.newsfeedbackend.rss.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestService
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.infrastructure.ArticleFetcher
import com.vdzon.newsfeedbackend.rss.infrastructure.RssFetcher
import com.vdzon.newsfeedbackend.rss.infrastructure.RssItemRepository
import com.vdzon.newsfeedbackend.settings.CategorySettings
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

@Component
class RssRefreshPipeline(
    private val rssRepo: RssItemRepository,
    private val fetcher: RssFetcher,
    private val articleFetcher: ArticleFetcher,
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
                .map { fetcher.fetch(it) }
                .toList()
                .flatten()
                .filter { it.url !in existingUrls }
                .distinctBy { it.url }

            log.info("[RSS] {} nieuwe artikelen voor '{}'", fetched.size, username)
            log.info("[RSS] stap 2/4: AI-samenvatting per artikel ({} stuks)", fetched.size)
            val processed = mutableListOf<RssItem>()
            fetched.forEachIndexed { idx, item ->
                val summarized = summarize(item, cats)
                if (summarized != null) processed.add(summarized)
                if ((idx + 1) % 5 == 0 || idx + 1 == fetched.size) {
                    log.info("[RSS]   samengevat {}/{}", idx + 1, fetched.size)
                }
            }
            rssRepo.upsertAll(username, processed)

            log.info("[RSS] stap 3/4: AI-selectie voor de persoonlijke feed ({} kandidaten)", processed.size)
            val selected = if (processed.isEmpty()) emptyList()
            else selectForFeed(username, processed, cats, existingItems)
            log.info("[RSS]   selectie: {} van {} artikelen geselecteerd", selected.size, processed.size)
            val withSelection = processed.map {
                val sel = selected.find { s -> s.first == it.id }
                if (sel != null) it.copy(inFeed = true, feedReason = sel.second)
                else it.copy(feedReason = "Niet geselecteerd voor de persoonlijke feed")
            }
            rssRepo.upsertAll(username, withSelection)

            val toFeed = withSelection.filter { it.inFeed }
            log.info("[RSS] stap 4/4: uitgebreide feed-samenvattingen genereren ({} stuks)", toFeed.size)
            var feedCount = 0
            for ((idx, rss) in toFeed.withIndex()) {
                log.info("[RSS]   feed-item {}/{}: {}", idx + 1, toFeed.size, rss.title.take(80))
                val feedItem = generateFeedItem(rss, cats)
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
            val r = requests.get(username, "hourly-update-$username")
            if (r != null) requests.upsert(username, r.copy(status = RequestStatus.FAILED, completedAt = Instant.now()))
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun summarize(rss: RssItem, categories: List<CategorySettings>): RssItem? {
        val catList = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) " — ${c.extraInstructions.take(200)}" else ""
            "- ${c.id}: ${c.name}$instr"
        }
        val ai = anthropic.complete(
            operation = "summarizeRssItem",
            model = anthropic.summaryModel(),
            system = "Je vat artikelen kort samen (150-250 woorden) in het Nederlands. Wijs een categorie-id toe op basis van de gebruikersvoorkeuren en extraheer 2-3 onderwerpen.",
            user = """
                Beschikbare categorieën (id, naam, gebruikersinstructies):
                $catList

                Artikel:
                Titel: ${rss.title}
                Bron: ${rss.source}
                Snippet: ${rss.snippet}

                Antwoord uitsluitend in geldig JSON met velden:
                {"summary": "Nederlandse samenvatting 150-250 woorden", "category": "kotlin", "topics": ["onderwerp 1", "onderwerp 2"]}
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

    private fun selectForFeed(
        username: String,
        items: List<RssItem>,
        categories: List<CategorySettings>,
        existing: List<RssItem>
    ): List<Pair<String, String>> {
        val titles = items.joinToString("\n") { "${it.id}|${it.category}|${it.title}" }
        val likedTitles = existing.filter { it.liked == true }.takeLast(20).joinToString("\n") { "+ ${it.title}" }
        val dislikedTitles = existing.filter { it.liked == false }.takeLast(20).joinToString("\n") { "- ${it.title}" }
        val starredTitles = existing.filter { it.starred }.takeLast(10).joinToString("\n") { "* ${it.title}" }
        val recentTopics = topicHistory.load(username)
            .sortedByDescending { (it.likedCount + it.starredCount) * 5 + it.newsCount }
            .take(15)
            .joinToString(", ") { "${it.topic}(news=${it.newsCount},liked=${it.likedCount})" }

        val catContext = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) "\n  voorkeur: ${c.extraInstructions}" else ""
            "${c.id} (${c.name})$instr"
        }

        val ai = anthropic.complete(
            operation = "selectFeedItems",
            system = "Je beoordeelt artikelen voor een persoonlijke feed van een softwareontwikkelaar. Gebruik de gebruikersvoorkeuren per categorie als leidraad. Selecteer alleen écht relevante artikelen — geen vast minimum of maximum. Geef per artikel een korte Nederlandse motivatie (max 1 zin) waarom je het wel of niet kiest.",
            user = """
                Categorieën en voorkeuren:
                $catContext

                Recent gelezen onderwerpen: ${recentTopics.ifBlank { "(geen)" }}

                Eerder geliket:
                ${likedTitles.ifBlank { "(geen)" }}

                Eerder afgewezen:
                ${dislikedTitles.ifBlank { "(geen)" }}

                Eerder bewaard (sterren):
                ${starredTitles.ifBlank { "(geen)" }}

                Nieuwe artikelen (id|category|title):
                $titles

                Antwoord uitsluitend met een geldig JSON-array. Voor élk artikel uit de lijst hierboven één entry, in dezelfde volgorde, in het format:
                [{"id": "...", "inFeed": true, "reason": "korte Nederlandse uitleg"}, ...]
            """.trimIndent()
        )
        return try {
            val tree = mapper.readTree(extractJson(ai.text))
            tree.filter { it.path("inFeed").asBoolean(false) }
                .map { it.path("id").asText() to it.path("reason").asText("") }
        } catch (e: Exception) {
            log.warn("[RSS] selectie parse fout: {}", e.message)
            log.debug("[RSS] selectie ruwe AI-output: {}", ai.text)
            emptyList()
        }
    }

    private fun generateFeedItem(rss: RssItem, categories: List<CategorySettings>): FeedItem {
        val fullText = articleFetcher.fetchPlainText(rss.url) ?: rss.snippet
        val catInstr = categories.find { it.id == rss.category }?.extraInstructions.orEmpty()
        val ai = anthropic.complete(
            operation = "generateFeedItemSummary",
            system = "Je schrijft een uitgebreide journalistieke samenvatting van 400-600 woorden in het Nederlands. " +
                "Geef context, betekenis en relevantie. Gebruik geen markdown-headers maar wel duidelijke paragrafen.",
            user = buildString {
                appendLine("Titel: ${rss.title}")
                appendLine("Bron: ${rss.source}")
                appendLine("URL: ${rss.url}")
                if (catInstr.isNotBlank()) {
                    appendLine()
                    appendLine("Lezerscontext (categorie '${rss.category}'): $catInstr")
                }
                appendLine()
                appendLine("Volledige artikeltekst (mogelijk afgekort):")
                append(fullText.take(8000))
            }
        )
        return FeedItem(
            id = UUID.randomUUID().toString(),
            title = rss.title,
            summary = ai.text.ifBlank { rss.summary.ifBlank { rss.snippet } },
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
