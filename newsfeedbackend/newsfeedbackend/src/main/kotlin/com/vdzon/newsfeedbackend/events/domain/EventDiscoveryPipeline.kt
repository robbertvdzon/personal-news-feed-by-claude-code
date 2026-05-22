package com.vdzon.newsfeedbackend.events.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.request.infrastructure.TavilyClient
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
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * KAN-65: ontdekt per gebruiker grote tech-events met Tavily web-search +
 * Claude, op basis van de ingeschakelde categorieën. Mirror van de
 * [com.vdzon.newsfeedbackend.rss.domain.RssRefreshPipeline]-architectuur:
 * een @Async @EventListener met een per-user ReentrantLock zodat een
 * handmatige trigger en de wekelijkse cron elkaar niet in de wielen rijden.
 *
 * Per ontdekt event:
 *  - dedup op de stabiele id (genormaliseerde naam + jaar) per gebruiker;
 *    een bestaand event wordt bijgewerkt, niet gedupliceerd.
 *  - bij een NIEUW event wordt een Nederlands aankondigings-FeedItem
 *    aangemaakt met verwijzing naar de Events-sectie.
 */
@Component
class EventDiscoveryPipeline(
    private val repo: EventRepository,
    private val tavily: TavilyClient,
    private val anthropic: AnthropicClient,
    private val settings: SettingsService,
    private val feed: FeedService,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    @EventListener
    @Async
    fun onDiscover(event: EventDiscoveryRequested) = run(event.username)

    fun run(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[Events] discovery already running for '{}'", username)
            return
        }
        MDC.put("username", username)
        try {
            val started = Instant.now()
            log.info("[Events] start event-discovery voor '{}'", username)
            val cats = settings.getCategories(username).filter { it.enabled && !it.isSystem }
            if (cats.isEmpty()) {
                log.info("[Events] geen ingeschakelde categorieën voor '{}' — niets te zoeken", username)
                return
            }
            // Mutable working-set zodat dedup ook binnen één run werkt
            // (twee categorieën kunnen hetzelfde event vinden).
            val existing = repo.load(username)
            val knownIds = existing.map { it.id }.toHashSet()
            var newCount = 0
            var updatedCount = 0

            for (cat in cats) {
                val query = "${cat.name} tech conference event keynote sessions 2025 2026"
                log.info("[Events] categorie '{}' → Tavily-search: {}", cat.id, query)
                val results = tavily.search(username, query, days = 365, maxResults = 12)
                if (results.isEmpty()) {
                    log.info("[Events]   geen zoekresultaten voor '{}'", cat.id)
                    continue
                }
                val discovered = extractEvents(username, cat, results)
                log.info("[Events]   AI haalde {} events uit {} resultaten voor '{}'",
                    discovered.size, results.size, cat.id)
                for (ev in discovered) {
                    if (!withinWindow(ev.startDate)) {
                        log.debug("[Events]   skip '{}' — buiten window (start={})", ev.id, ev.startDate)
                        continue
                    }
                    val prior = existing.find { it.id == ev.id }
                    if (prior != null) {
                        repo.upsert(username, ev.copy(
                            feedItemId = prior.feedItemId,
                            createdAt = prior.createdAt,
                            updatedAt = Instant.now()
                        ))
                        updatedCount++
                    } else {
                        val feedItem = announcementFeedItem(ev)
                        feed.save(username, feedItem)
                        val saved = ev.copy(feedItemId = feedItem.id)
                        repo.upsert(username, saved)
                        existing.add(saved)
                        knownIds.add(saved.id)
                        newCount++
                        log.info("[Events]   NIEUW event '{}' ({}) + aankondiging in feed", ev.id, ev.name)
                    }
                }
            }

            meters.counter("newsfeed.events.discovered", "username", username).increment(newCount.toDouble())
            meters.timer("newsfeed.events.discovery.duration", "username", username)
                .record(Duration.between(started, Instant.now()))
            val took = Duration.between(started, Instant.now()).seconds.toInt()
            log.info("[Events] klaar voor '{}': {} nieuw, {} bijgewerkt, duur {}s",
                username, newCount, updatedCount, took)
        } catch (e: Exception) {
            log.error("[Events] discovery mislukt voor '{}': {}", username, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun extractEvents(
        username: String,
        cat: CategorySettings,
        results: List<com.vdzon.newsfeedbackend.request.infrastructure.TavilyResult>
    ): List<Event> {
        val today = LocalDate.now()
        val sources = results.joinToString("\n\n") { r ->
            "URL: ${r.url}\nTitel: ${r.title}\nFragment: ${r.snippet.take(500)}"
        }
        val instr = if (cat.extraInstructions.isNotBlank()) "\nVoorkeur van de gebruiker: ${cat.extraInstructions}" else ""
        val ai = anthropic.complete(
            operation = "discoverEvents",
            action = ExternalCall.ACTION_EVENT_DISCOVERY,
            username = username,
            subject = "Events voor categorie ${cat.name}",
            model = anthropic.mainModel(),
            maxTokens = 8000,
            system = """
                Je bent een tech-event-analist. Uit zoekresultaten haal je grote, relevante
                tech-events: conferenties zoals JavaOne, KotlinConf, Spring I/O, Devoxx,
                KubeCon, Google I/O, OpenAI DevDay, Code with Claude. Negeer kleine meetups,
                webinars, cursussen en niet-tech events.

                Regels:
                - Geef per event een stabiele id: genormaliseerde naam + jaar in kleine letters
                  met streepjes, bijv. "javaone-2026", "kotlinconf-2025", "spring-io-2026".
                - Begin- en einddatum in YYYY-MM-DD. Laat null wanneer je de datum niet zeker weet.
                - De beschrijving is in het NEDERLANDS en benoemt de onderwerpen/thema's.
                - organization mag null zijn wanneer onbekend.
                - sourceLinks: de URL('s) uit de zoekresultaten waar de info vandaan komt.
                - Antwoord met ALLEEN een pure JSON-array, geen markdown-codefences (geen ```),
                  geen prose ervoor of erna.
            """.trimIndent(),
            user = """
                Vandaag is het $today. Zoek events die nog komen of die maximaal één jaar
                geleden waren (dus vanaf ${today.minusYears(1)}).

                Categorie: ${cat.name}$instr

                Zoekresultaten:
                $sources

                Antwoord met een JSON-array. Voor elk event één object:
                [{"id":"javaone-2026","name":"JavaOne 2026","organization":"Oracle",
                  "startDate":"2026-03-17","endDate":"2026-03-20","location":"Redwood Shores, CA",
                  "description":"Nederlandse beschrijving van de onderwerpen","sourceLinks":["https://..."]}]
            """.trimIndent()
        )
        return try {
            val tree = mapper.readTree(extractJson(ai.text))
            if (!tree.isArray) {
                log.warn("[Events] AI gaf geen JSON-array voor '{}' — eerste 300 chars: {}",
                    cat.id, ai.text.take(300))
                return emptyList()
            }
            tree.mapNotNull { node ->
                val name = node.path("name").asText("").trim()
                if (name.isBlank()) return@mapNotNull null
                val rawId = node.path("id").asText("").ifBlank { name }
                Event(
                    id = normalizeId(rawId),
                    name = name,
                    organization = node.path("organization").asText(null)?.ifBlank { null },
                    startDate = node.path("startDate").asText(null)?.ifBlank { null },
                    endDate = node.path("endDate").asText(null)?.ifBlank { null },
                    location = node.path("location").asText(""),
                    description = node.path("description").asText(""),
                    sourceLinks = node.path("sourceLinks").mapNotNull { it.asText(null) }.filter { it.isNotBlank() },
                    category = cat.id
                )
            }
        } catch (e: Exception) {
            log.warn("[Events] parse-fout voor '{}': {} — eerste 300 chars: {}",
                cat.id, e.message, ai.text.take(300))
            emptyList()
        }
    }

    private fun announcementFeedItem(ev: Event): FeedItem {
        val whenStr = formatDutchDate(ev.startDate)
        val org = ev.organization?.takeIf { it.isNotBlank() }?.let { " van $it" } ?: ""
        val loc = ev.location.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""
        val short = "Op $whenStr is er het tech-event ${ev.name}$org$loc gepland."
        val long = buildString {
            append("Op $whenStr is er ${ev.name}$org$loc gepland.")
            if (ev.description.isNotBlank()) {
                append(" Met deze onderwerpen: ${ev.description}")
            }
            append("\n\nBekijk dit event in de Events-sectie voor de volledige beschrijving, datum, locatie en bronlinks.")
        }
        return FeedItem(
            id = UUID.randomUUID().toString(),
            title = ev.name,
            titleNl = ev.name,
            summary = long,
            shortSummary = short,
            url = ev.sourceLinks.firstOrNull(),
            category = ev.category,
            source = ev.organization ?: "",
            sourceUrls = ev.sourceLinks,
            topics = listOf(ev.name),
            feedReason = "Automatisch ontdekt tech-event — zie de Events-sectie",
            publishedDate = ev.startDate,
            createdAt = Instant.now(),
            mediaType = "ARTICLE"
        )
    }

    /** Houd events die in de toekomst liggen of maximaal één jaar terug zijn. */
    private fun withinWindow(startDate: String?): Boolean {
        if (startDate == null) return true // datum onbekend → behouden, gebruiker beslist
        val d = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return true
        return !d.isBefore(LocalDate.now().minusYears(1))
    }

    /** Normaliseer naar een stabiele dedup-sleutel: lowercase, alfanumeriek + streepjes. */
    private fun normalizeId(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "event-${UUID.randomUUID()}" }

    private fun formatDutchDate(date: String?): String {
        if (date == null) return "binnenkort"
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val month = d.month.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("nl"))
        return "${d.dayOfMonth} $month ${d.year}"
    }

    /**
     * Pulls the JSON payload out of a Claude response — strips markdown
     * fences en zoekt het eerste gebalanceerde array/object. Kopie van de
     * helper in RssRefreshPipeline; bewust niet uitgefactored om de
     * modules ontkoppeld te houden.
     */
    private fun extractJson(text: String): String {
        var s = text.trim()
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
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
            if (escape) { escape = false; continue }
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
        return s.substring(start)
    }
}
