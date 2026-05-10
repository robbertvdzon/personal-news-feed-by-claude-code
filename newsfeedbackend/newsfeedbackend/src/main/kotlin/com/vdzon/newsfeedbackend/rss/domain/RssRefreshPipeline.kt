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

    @EventListener
    @Async
    fun onReselect(event: RssReselectRequested) = reselect(event.username)

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
                val summarized = summarize(username, item, cats)
                if (summarized != null) processed.add(summarized)
                if ((idx + 1) % 5 == 0 || idx + 1 == fetched.size) {
                    log.info("[RSS]   samengevat {}/{}", idx + 1, fetched.size)
                }
            }
            rssRepo.upsertAll(username, processed)

            log.info("[RSS] stap 3/4: AI-selectie voor de persoonlijke feed ({} kandidaten)", processed.size)
            val selectionResults = if (processed.isEmpty()) emptyMap()
            else selectForFeed(username, processed, cats, existingItems)
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
                val feedItem = generateFeedItem(username, rss, cats)
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
            val verdicts = selectForFeed(username, all, cats, all)
            if (verdicts.isEmpty()) {
                log.warn("[RSS] reselect: AI gaf geen verdicts terug — bestaande inFeed/feedReason ongewijzigd. Check ANTHROPIC_API_KEY of de selectie-prompt.")
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
                val feedItem = generateFeedItem(username, rss, cats)
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

    private fun summarize(username: String, rss: RssItem, categories: List<CategorySettings>): RssItem? {
        val catList = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) " — ${c.extraInstructions.take(200)}" else ""
            "- ${c.id}: ${c.name}$instr"
        }
        val ai = anthropic.complete(
            operation = "summarizeRssItem",
            action = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_RSS_SUMMARIZE,
            username = username,
            subject = rss.title.take(120),
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

    data class SelectionVerdict(val inFeed: Boolean, val reason: String)

    private fun selectForFeed(
        username: String,
        items: List<RssItem>,
        categories: List<CategorySettings>,
        existing: List<RssItem>
    ): Map<String, SelectionVerdict> {
        val titles = items.joinToString("\n") {
            // include the AI-generated summary head + topics so Claude has more
            // signal than just title — useful when titles are vague.
            val headline = it.summary.take(180).ifBlank { it.snippet.take(180) }
            val topics = if (it.topics.isNotEmpty()) " [topics: ${it.topics.joinToString(", ")}]" else ""
            "${it.id}|${it.category}|${it.title}$topics\n  → $headline"
        }
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
            action = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_FEED_SCORE,
            username = username,
            subject = "${items.size} kandidaten",
            maxTokens = 16000,
            system = """
                Je bent een nieuwsredacteur die voor een softwareontwikkelaar bepaalt welke artikelen interessant genoeg zijn voor zijn persoonlijke feed.

                Belangrijk:
                - Gebruik de 'voorkeur'-tekst per categorie actief: een artikel dat past bij de voorkeur is in principe relevant.
                - Wees niet te streng. Twijfelgevallen die binnen de gebruikers­voorkeuren vallen mogen worden meegenomen.
                - Schrijf de redenen in het Nederlands, max 1 zin.
                - Beantwoord álle aangeleverde artikelen, in dezelfde volgorde.
                - Antwoord met **alleen** de pure JSON-array, geen markdown-codefences (geen ```), geen prose ervoor of erna.
            """.trimIndent(),
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

                Te beoordelen artikelen (id|category|title [topics] → samenvattingsbegin):
                $titles

                Antwoord met een geldig JSON-array (geen prose ervoor of erna). Voor élk id één entry in dezelfde volgorde:
                [{"id": "...", "inFeed": true, "reason": "korte Nederlandse uitleg"}]
                Gebruik inFeed=true voor relevante artikelen, inFeed=false voor de rest.
            """.trimIndent()
        )
        log.debug("[RSS] selectie ruwe AI-output ({} chars): {}", ai.text.length, ai.text.take(2000))
        return try {
            val tree = mapper.readTree(extractJson(ai.text))
            if (!tree.isArray) {
                log.warn("[RSS] selectie: AI gaf geen JSON-array terug — eerste 500 chars: {}", ai.text.take(500))
                return emptyMap()
            }
            val results = mutableMapOf<String, SelectionVerdict>()
            var inFeedCount = 0
            for (node in tree) {
                val id = node.path("id").asText("")
                if (id.isBlank()) continue
                val inFeed = node.path("inFeed").asBoolean(false)
                val reason = node.path("reason").asText("")
                results[id] = SelectionVerdict(inFeed, reason)
                if (inFeed) inFeedCount++
            }
            log.info("[RSS]   selectie response: {} entries beoordeeld ({} inFeed=true, {} inFeed=false)",
                results.size, inFeedCount, results.size - inFeedCount)
            if (inFeedCount == 0 && results.isNotEmpty()) {
                val sampleReasons = results.entries.take(3).joinToString(" | ") { (id, v) ->
                    val title = items.find { it.id == id }?.title?.take(40).orEmpty()
                    "[$title] ${v.reason.take(80)}"
                }
                log.info("[RSS]   AI heeft alle artikelen afgewezen — voorbeelden: {}", sampleReasons)
            }
            results
        } catch (e: Exception) {
            log.warn("[RSS] selectie parse fout: {} — eerste 500 chars: {}", e.message, ai.text.take(500))
            emptyMap()
        }
    }

    private fun generateFeedItem(username: String, rss: RssItem, categories: List<CategorySettings>): FeedItem {
        val fullText = articleFetcher.fetchPlainText(username, rss.url) ?: rss.snippet
        val catInstr = categories.find { it.id == rss.category }?.extraInstructions.orEmpty()
        val ai = anthropic.complete(
            operation = "generateFeedItemSummary",
            action = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_FEED_SUMMARIZE,
            username = username,
            subject = rss.title.take(120),
            maxTokens = 4000,
            system = """
                Je schrijft drie velden voor een persoonlijk nieuwsoverzicht in het Nederlands.

                titleNl: Korte beschrijvende titel (max 70 tekens) die in één oogopslag duidelijk maakt waar het artikel over gaat. Géén opsmuk, geen punten aan het eind, geen quotes.
                shortSummary: Twee regels Nederlandse samenvatting (~30-50 woorden, plain text — GÉÉN markdown). Vat de kern van het nieuws samen zoals een teaser onder een krantenkop. Eindig met een punt.
                longSummary: Uitgebreide journalistieke samenvatting van 400-600 woorden in het Nederlands. Geef context, betekenis en relevantie. Gebruik géén markdown-headers (`#`), maar **vet** voor begrippen en aparte paragrafen mogen.

                Antwoord uitsluitend met geldig JSON, geen markdown-codefences (geen ```), geen prose ervoor of erna.
            """.trimIndent(),
            user = buildString {
                appendLine("Originele titel: ${rss.title}")
                appendLine("Bron: ${rss.source}")
                appendLine("URL: ${rss.url}")
                if (catInstr.isNotBlank()) {
                    appendLine()
                    appendLine("Lezerscontext (categorie '${rss.category}'): $catInstr")
                }
                appendLine()
                appendLine("Volledige artikeltekst (mogelijk afgekort):")
                appendLine(fullText.take(8000))
                appendLine()
                appendLine("Antwoord met JSON in dit format:")
                append("""{"titleNl": "...", "shortSummary": "...", "longSummary": "..."}""")
            }
        )
        log.debug("[RSS] feed-item ruwe AI-output ({} chars): {}", ai.text.length, ai.text.take(800))

        var titleNl = ""
        var shortSummary = ""
        var longSummary = ""
        try {
            val tree = mapper.readTree(extractJson(ai.text))
            titleNl = tree.path("titleNl").asText("").trim()
            shortSummary = tree.path("shortSummary").asText("").trim()
            longSummary = tree.path("longSummary").asText("").trim()
        } catch (e: Exception) {
            log.warn("[RSS] feed-item parse fout voor '{}': {} — eerste 500 chars: {}",
                rss.title, e.message, ai.text.take(500))
        }
        // Fallback-keten zodat een mislukte parse de feed niet leeg laat:
        if (longSummary.isBlank()) longSummary = ai.text.ifBlank { rss.summary.ifBlank { rss.snippet } }
        if (shortSummary.isBlank()) shortSummary = rss.summary.take(200).ifBlank { rss.snippet.take(200) }
        if (titleNl.isBlank()) titleNl = rss.title

        return FeedItem(
            id = UUID.randomUUID().toString(),
            title = rss.title,
            titleNl = titleNl,
            summary = longSummary,
            shortSummary = shortSummary,
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

    /**
     * Pulls the JSON payload out of a Claude response.
     *
     * Claude routinely wraps its answers in ```json ... ``` markdown fences,
     * so we strip those first. After that we find the earliest opening
     * bracket — `[` for an array, `{` for an object — and walk forward
     * tracking brace/bracket depth (respecting strings + escape chars) to
     * locate the matching close. That gives us a clean balanced segment
     * even when Claude appends prose after the JSON, and it avoids the
     * old bug where `indexOf('{')` picked up a position *inside* the array
     * (the first object's opener) and skipped the array's own `[`.
     *
     * If the depth never closes (response truncated by max_tokens), we
     * return everything from the opener onwards — the caller will get a
     * Jackson parse error and log the raw text for debugging.
     */
    private fun extractJson(text: String): String {
        var s = text.trim()
        // Strip leading ```json or ``` fence
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
        // Strip trailing ``` fence
        if (s.endsWith("```")) s = s.dropLast(3).trim()

        val curly = s.indexOf('{')
        val bracket = s.indexOf('[')
        val start = when {
            curly < 0 && bracket < 0 -> return s
            curly < 0 -> bracket
            bracket < 0 -> curly
            else -> minOf(curly, bracket)
        }
        val openChar = s[start]
        val closeChar = if (openChar == '{') '}' else ']'

        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        // Unbalanced — return from opener; Jackson will fail and the
        // caller logs a parse error with the raw text.
        return s.substring(start)
    }
}
