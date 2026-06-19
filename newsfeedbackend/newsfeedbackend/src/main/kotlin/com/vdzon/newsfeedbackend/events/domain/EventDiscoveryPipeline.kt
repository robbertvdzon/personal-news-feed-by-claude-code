package com.vdzon.newsfeedbackend.events.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.request.infrastructure.TavilyClient
import com.vdzon.newsfeedbackend.request.infrastructure.TavilyResult
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
 * KAN-65 + KAN-68: ontdekt per gebruiker grote tech-events met Tavily
 * web-search + Claude.
 *
 * KAN-68 verandert de seed-strategie:
 *  - Primaire bron: de per-user lijst event-voorkeuren (vrije namen,
 *    bv. "JavaOne", "KotlinConf"). Per naam doen we één gerichte
 *    Tavily-search + één Claude-extract.
 *  - Secundaire bron: de bestaande categorie-settings (KAN-65 gedrag).
 *  - Ná de seed-pass één extra "similar"-Claude-call die op basis van de
 *    voorkeuren-lijst soortgelijke events binnen dezelfde scene/community
 *    voorstelt. Cap: 1 extra call per run per user.
 *  - Events zonder valide start_date krijgen één extra Tavily-lookup;
 *    levert die nog steeds niets op dan wordt het event verworpen.
 *  - De denylist filtert weg: een eerder verwijderd event wordt niet
 *    opnieuw aangemaakt.
 *
 * Per-user ReentrantLock + Spring @EventListener/@Async — identiek
 * patroon als [com.vdzon.newsfeedbackend.rss.domain.RssRefreshPipeline].
 */
@Component
class EventDiscoveryPipeline(
    private val repo: EventRepository,
    private val tavily: TavilyClient,
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val settings: SettingsService,
    private val feed: FeedService,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Max aantal voorkeuren dat we per run als seed gebruiken. Boven
     * deze cap kappen we af zodat één gebruiker met een rare lange
     * lijst (50+ namen) niet ineens 50× Tavily belt.
     */
    private val maxSeedQueries = 20

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

            val preferences = settings.getEventPreferences(username).names.take(maxSeedQueries)
            val denylist = settings.getEventDenylist(username).entries.map { it.normalizedId }.toHashSet()
            val cats = settings.getCategories(username).filter { it.enabled && !it.isSystem }

            if (preferences.isEmpty() && cats.isEmpty()) {
                log.info("[Events] geen voorkeuren én geen categorieën voor '{}' — niets te zoeken", username)
                return
            }

            val existing = repo.load(username)
            var newCount = 0
            var updatedCount = 0
            var rejectedNoDate = 0
            var rejectedDenylisted = 0

            // ── 1. Per voorkeur (PRIMAIRE seed) ──────────────────────
            for (pref in preferences) {
                val today = LocalDate.now()
                val year = today.year
                val query = "$pref conference $year ${year + 1} dates location"
                log.info("[Events] seed '{}' → Tavily-search: {}", pref, query)
                val results = tavily.search(username, query, days = 365, maxResults = 10)
                if (results.isEmpty()) {
                    log.info("[Events]   geen zoekresultaten voor seed '{}'", pref)
                    continue
                }
                val discovered = extractEventsForSeed(username, pref, results)
                log.info("[Events]   AI haalde {} events uit {} resultaten voor seed '{}'",
                    discovered.size, results.size, pref)
                val outcome = persistDiscovered(
                    username, discovered, existing, denylist
                )
                newCount += outcome.created
                updatedCount += outcome.updated
                rejectedNoDate += outcome.rejectedNoDate
                rejectedDenylisted += outcome.rejectedDenylisted
            }

            // ── 2. Eén "similar"-call op basis van de voorkeuren ────
            if (preferences.isNotEmpty()) {
                val similar = discoverSimilar(username, preferences)
                if (similar.isNotEmpty()) {
                    log.info("[Events] similar-call gaf {} kandidaten", similar.size)
                    val outcome = persistDiscovered(
                        username, similar, existing, denylist
                    )
                    newCount += outcome.created
                    updatedCount += outcome.updated
                    rejectedNoDate += outcome.rejectedNoDate
                    rejectedDenylisted += outcome.rejectedDenylisted
                }
            }

            // ── 3. Secundair: categorie-gebaseerde discovery (KAN-65) ─
            for (cat in cats) {
                val query = "${cat.name} tech conference event keynote sessions 2025 2026"
                log.info("[Events] categorie '{}' → Tavily-search: {}", cat.id, query)
                val results = tavily.search(username, query, days = 365, maxResults = 12)
                if (results.isEmpty()) {
                    log.info("[Events]   geen zoekresultaten voor '{}'", cat.id)
                    continue
                }
                val discovered = extractEventsForCategory(username, cat, results)
                log.info("[Events]   AI haalde {} events uit {} resultaten voor '{}'",
                    discovered.size, results.size, cat.id)
                val outcome = persistDiscovered(
                    username, discovered, existing, denylist
                )
                newCount += outcome.created
                updatedCount += outcome.updated
                rejectedNoDate += outcome.rejectedNoDate
                rejectedDenylisted += outcome.rejectedDenylisted
            }

            meters.counter("newsfeed.events.discovered", "username", username).increment(newCount.toDouble())
            meters.timer("newsfeed.events.discovery.duration", "username", username)
                .record(Duration.between(started, Instant.now()))
            val took = Duration.between(started, Instant.now()).seconds.toInt()
            log.info(
                "[Events] klaar voor '{}': {} nieuw, {} bijgewerkt, {} verworpen (no-date), {} overgeslagen (denylist), duur {}s",
                username, newCount, updatedCount, rejectedNoDate, rejectedDenylisted, took
            )
        } catch (e: Exception) {
            log.error("[Events] discovery mislukt voor '{}': {}", username, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private data class PersistOutcome(
        val created: Int,
        val updated: Int,
        val rejectedNoDate: Int,
        val rejectedDenylisted: Int
    )

    /**
     * Verwerkt een verzameling discovered events: dedup, denylist,
     * date-recovery-via-Tavily, opslag + aankondigings-FeedItem voor
     * écht nieuwe events. De `existing`-lijst wordt in-place
     * geüpdatet zodat een tweede call binnen dezelfde run ook
     * direct dedup op de net-aangemaakte events.
     */
    private fun persistDiscovered(
        username: String,
        discovered: List<Event>,
        existing: MutableList<Event>,
        denylist: HashSet<String>
    ): PersistOutcome {
        var created = 0
        var updated = 0
        var rejectedNoDate = 0
        var rejectedDenylisted = 0

        for (raw in discovered) {
            if (raw.id in denylist) {
                log.debug("[Events]   skip '{}' — staat op denylist", raw.id)
                rejectedDenylisted++
                continue
            }
            // KAN-68 AC: events zonder geldige start_date worden niet opgeslagen.
            // Probeer eerst één extra Tavily-lookup om de datum te
            // vinden voordat we 'm weggooien.
            val ev = ensureStartDate(username, raw)
            if (!hasValidStartDate(ev.startDate)) {
                log.info("[Events]   verwerp '{}' ({}) — geen geldige datum gevonden", ev.id, ev.name)
                rejectedNoDate++
                continue
            }
            if (!withinWindow(ev.startDate)) {
                log.debug("[Events]   skip '{}' — buiten window (start={})", ev.id, ev.startDate)
                continue
            }
            val prior = existing.find { it.id == ev.id }
            if (prior != null) {
                val merged = ev.copy(
                    feedItemId = prior.feedItemId,
                    createdAt = prior.createdAt,
                    updatedAt = Instant.now()
                )
                repo.upsert(username, merged)
                val idx = existing.indexOf(prior)
                if (idx >= 0) existing[idx] = merged
                updated++
            } else {
                val feedItem = announcementFeedItem(ev)
                feed.save(username, feedItem)
                val saved = ev.copy(feedItemId = feedItem.id)
                repo.upsert(username, saved)
                existing.add(saved)
                created++
                log.info("[Events]   NIEUW event '{}' ({}) + aankondiging in feed", ev.id, ev.name)
            }
        }
        return PersistOutcome(created, updated, rejectedNoDate, rejectedDenylisted)
    }

    /** True alleen voor een ISO-8601 YYYY-MM-DD-string die parsebaar is. */
    private fun hasValidStartDate(startDate: String?): Boolean {
        if (startDate.isNullOrBlank()) return false
        return runCatching { LocalDate.parse(startDate) }.isSuccess
    }

    /**
     * Eén extra Tavily-lookup voor events zonder valide datum. We
     * proberen één gerichte query ("<naam> dates 2025 2026") en
     * vragen Claude alleen om een datum te extraheren. Faalt dat,
     * dan komt het event terug met onveranderde (nog steeds null)
     * startDate en wordt later in de pipeline verworpen.
     */
    private fun ensureStartDate(username: String, ev: Event): Event {
        if (hasValidStartDate(ev.startDate)) return ev
        val today = LocalDate.now()
        val query = "${ev.name} conference dates ${today.year} ${today.year + 1}"
        log.info("[Events]   date-lookup voor '{}': {}", ev.id, query)
        val results = tavily.search(username, query, days = 365, maxResults = 6)
        if (results.isEmpty()) return ev
        return enrichWithDate(username, ev, results)
    }

    private fun enrichWithDate(username: String, ev: Event, results: List<TavilyResult>): Event {
        val sources = results.joinToString("\n\n") { r ->
            "URL: ${r.url}\nTitel: ${r.title}\nFragment: ${r.snippet.take(500)}"
        }
        // SF-115: de lichte datum-verrijking gebruikt een eigen (goedkoper, nano)
        // config-key, maar logt nog onder de event_discovery-actie.
        val ai = openAi.complete(
            model = aiModels.modelFor("event_discovery_date") ?: "gpt-5.4-nano",
            action = ExternalCall.ACTION_EVENT_DISCOVERY,
            username = username,
            subject = "Datum-lookup voor ${ev.name}",
            maxOutputTokens = 500,
            system = """
                Je bent een tech-event-analist. Uit zoekresultaten haal je de
                begin- en einddatum van één specifiek event.

                Regels:
                - startDate / endDate in YYYY-MM-DD-formaat. Laat null wanneer
                  je echt geen datum kunt vinden.
                - Antwoord met ALLEEN een puur JSON-object, geen markdown-fences,
                  geen prose ervoor of erna.
            """.trimIndent(),
            user = """
                Event: ${ev.name}${ev.organization?.let { " (van $it)" } ?: ""}

                Zoekresultaten:
                $sources

                Antwoord met een JSON-object:
                {"startDate":"2026-03-17","endDate":"2026-03-20"}
            """.trimIndent()
        )
        return try {
            val tree = mapper.readTree(extractJson(ai.text))
            val start = tree.path("startDate").asText(null)?.takeIf { it.isNotBlank() }
            val end = tree.path("endDate").asText(null)?.takeIf { it.isNotBlank() }
            if (start == null || runCatching { LocalDate.parse(start) }.isFailure) {
                ev
            } else {
                ev.copy(
                    startDate = start,
                    endDate = end ?: ev.endDate,
                    sourceLinks = (ev.sourceLinks + results.map { it.url }).distinct()
                )
            }
        } catch (e: Exception) {
            log.warn("[Events]   date-lookup parse-fout voor '{}': {}", ev.id, e.message)
            ev
        }
    }

    /**
     * Voor één voorkeur (vrije naam) uit zoekresultaten events
     * extraheren. De prompt stuurt expliciet op die specifieke naam —
     * we accepteren ook duidelijke jaar-varianten ("JavaOne 2026"
     * matched op "JavaOne").
     */
    private fun extractEventsForSeed(
        username: String,
        seedName: String,
        results: List<TavilyResult>
    ): List<Event> {
        val today = LocalDate.now()
        val sources = results.joinToString("\n\n") { r ->
            "URL: ${r.url}\nTitel: ${r.title}\nFragment: ${r.snippet.take(500)}"
        }
        val ai = openAi.complete(
            model = aiModels.modelFor(ExternalCall.ACTION_EVENT_DISCOVERY) ?: "gpt-5.4-mini",
            action = ExternalCall.ACTION_EVENT_DISCOVERY,
            username = username,
            subject = "Seed-event '$seedName'",
            maxOutputTokens = 4000,
            system = """
                Je bent een tech-event-analist. Uit zoekresultaten haal je de
                edities van één specifiek event (de "seed") en eventueel
                gerelateerde, sterk overlappende edities (bv. een regionaal
                zusterevent met dezelfde organisatie).

                Regels:
                - Geef per editie een stabiele id: genormaliseerde naam + jaar in
                  kleine letters met streepjes, bv. "javaone-2026", "kubecon-eu-2026".
                - Begin- en einddatum in YYYY-MM-DD. Laat null wanneer je de
                  datum niet zeker weet — er volgt nog een extra check.
                - De beschrijving is in het NEDERLANDS en benoemt onderwerpen/thema's.
                - organization mag null zijn wanneer onbekend.
                - sourceLinks: de URL('s) waar de info vandaan komt.
                - Negeer kleine meetups, webinars, cursussen, niet-tech events.
                - Antwoord met ALLEEN een pure JSON-array, geen markdown-codefences,
                  geen prose ervoor of erna.
            """.trimIndent(),
            user = """
                Vandaag is het $today. We zoeken alleen edities die nog komen of
                die maximaal één jaar geleden waren (dus vanaf ${today.minusYears(1)}).

                Seed-event: $seedName

                Zoekresultaten:
                $sources

                Antwoord met een JSON-array. Voor elke editie één object:
                [{"id":"javaone-2026","name":"JavaOne 2026","organization":"Oracle",
                  "startDate":"2026-03-17","endDate":"2026-03-20","location":"Redwood Shores, CA",
                  "description":"Nederlandse beschrijving van de onderwerpen","sourceLinks":["https://..."]}]
            """.trimIndent()
        )
        return parseEvents(ai.text, "seed:$seedName", "overig")
    }

    /**
     * Eén Claude-call die op basis van de hele voorkeuren-lijst
     * vergelijkbare events binnen dezelfde scene/community/technologie
     * voorstelt. Geen Tavily-grounding — Claude valt terug op zijn
     * eigen kennis. Cap: 1 call per run.
     */
    private fun discoverSimilar(username: String, preferences: List<String>): List<Event> {
        val today = LocalDate.now()
        val prefList = preferences.joinToString("\n") { "- $it" }
        val ai = openAi.complete(
            model = aiModels.modelFor(ExternalCall.ACTION_EVENT_DISCOVERY) ?: "gpt-5.4-mini",
            action = ExternalCall.ACTION_EVENT_DISCOVERY,
            username = username,
            subject = "Vergelijkbare events voor ${preferences.size} voorkeuren",
            maxOutputTokens = 4000,
            system = """
                Je bent een tech-event-analist. Op basis van een lijst events
                waar de gebruiker in geïnteresseerd is stel je vergelijkbare
                edities voor: events binnen dezelfde scene, community of
                technologie. Bv. iemand met "KotlinConf" en "Devoxx" zou ook
                "JetBrains Day" en "Devoxx UK" willen zien.

                Regels:
                - Geef alleen events die in de toekomst liggen of maximaal één
                  jaar geleden waren.
                - Stel maximaal 12 events voor; kies kwaliteit boven kwantiteit.
                - Geef per event een stabiele id: genormaliseerde naam + jaar.
                - Begin- en einddatum in YYYY-MM-DD wanneer je 'm met zekerheid
                  weet, anders null.
                - De beschrijving is in het NEDERLANDS en legt kort uit waarom
                  dit event matcht ("vergelijkbaar met …").
                - Geen events die letterlijk in de voorkeuren-lijst staan.
                - Antwoord met ALLEEN een pure JSON-array, geen markdown-fences.
            """.trimIndent(),
            user = """
                Vandaag is het $today. Hier is de voorkeuren-lijst van de gebruiker:

                $prefList

                Antwoord met een JSON-array. Voor elk vergelijkbaar event één object:
                [{"id":"jetbrains-day-2026","name":"JetBrains Day 2026","organization":"JetBrains",
                  "startDate":null,"endDate":null,"location":"Online",
                  "description":"Nederlandse beschrijving + waarom dit matcht","sourceLinks":[]}]
            """.trimIndent()
        )
        return parseEvents(ai.text, "similar", "overig")
    }

    private fun extractEventsForCategory(
        username: String,
        cat: CategorySettings,
        results: List<TavilyResult>
    ): List<Event> {
        val today = LocalDate.now()
        val sources = results.joinToString("\n\n") { r ->
            "URL: ${r.url}\nTitel: ${r.title}\nFragment: ${r.snippet.take(500)}"
        }
        val instr = if (cat.extraInstructions.isNotBlank()) "\nVoorkeur van de gebruiker: ${cat.extraInstructions}" else ""
        val ai = openAi.complete(
            model = aiModels.modelFor(ExternalCall.ACTION_EVENT_DISCOVERY) ?: "gpt-5.4-mini",
            action = ExternalCall.ACTION_EVENT_DISCOVERY,
            username = username,
            subject = "Events voor categorie ${cat.name}",
            maxOutputTokens = 8000,
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
        return parseEvents(ai.text, "category:${cat.id}", cat.id)
    }

    /**
     * Common parsing van een JSON-array Claude-response naar [Event]s.
     * `tag` is alleen voor logging zodat we in de stack zien welke
     * seed/categorie de output gaf.
     */
    private fun parseEvents(text: String, tag: String, defaultCategory: String): List<Event> {
        return try {
            val tree = mapper.readTree(extractJson(text))
            if (!tree.isArray) {
                log.warn("[Events] AI gaf geen JSON-array voor '{}' — eerste 300 chars: {}", tag, text.take(300))
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
                    category = defaultCategory
                )
            }
        } catch (e: Exception) {
            log.warn("[Events] parse-fout voor '{}': {} — eerste 300 chars: {}", tag, e.message, text.take(300))
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
        if (startDate == null) return false // KAN-68: null mag hier niet meer doorkomen
        val d = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return false
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
