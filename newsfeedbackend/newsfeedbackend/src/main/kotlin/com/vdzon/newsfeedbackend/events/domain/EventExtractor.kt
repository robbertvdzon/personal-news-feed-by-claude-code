package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.ai.AiJson
import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.search.TavilyClient
import com.vdzon.newsfeedbackend.search.TavilyResult
import com.vdzon.newsfeedbackend.settings.CategorySettings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * Levert kandidaat-events op via Tavily web-search + AI-extractie, in
 * drie varianten (zie [EventDiscoveryPipeline] voor de orkestratie):
 *
 *  - [discoverForSeed]: één gerichte search + extract per event-voorkeur
 *    (vrije naam, bv. "JavaOne") — de primaire seed (KAN-68).
 *  - [discoverSimilar]: één AI-call zonder Tavily-grounding die op basis
 *    van de hele voorkeuren-lijst vergelijkbare events voorstelt.
 *  - [discoverForCategory]: categorie-gebaseerde discovery (KAN-65 gedrag).
 */
@Component
class EventExtractor(
    private val tavily: TavilyClient,
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Voor één voorkeur (vrije naam): Tavily-search + uit de resultaten
     * events extraheren. De prompt stuurt expliciet op die specifieke
     * naam — we accepteren ook duidelijke jaar-varianten ("JavaOne 2026"
     * matched op "JavaOne").
     */
    fun discoverForSeed(username: String, seedName: String): List<Event> {
        val today = LocalDate.now()
        val year = today.year
        val query = "$seedName conference $year ${year + 1} dates location"
        log.info("[Events] seed '{}' → Tavily-search: {}", seedName, query)
        val results = tavily.search(username, query, days = 365, maxResults = 10)
        if (results.isEmpty()) {
            log.info("[Events]   geen zoekresultaten voor seed '{}'", seedName)
            return emptyList()
        }
        val discovered = extractEventsForSeed(username, seedName, results)
        log.info("[Events]   AI haalde {} events uit {} resultaten voor seed '{}'",
            discovered.size, results.size, seedName)
        return discovered
    }

    /**
     * Categorie-gebaseerde discovery (KAN-65): Tavily-search op de
     * categorienaam + uit de resultaten events extraheren.
     */
    fun discoverForCategory(username: String, cat: CategorySettings): List<Event> {
        val query = "${cat.name} tech conference event keynote sessions 2025 2026"
        log.info("[Events] categorie '{}' → Tavily-search: {}", cat.id, query)
        val results = tavily.search(username, query, days = 365, maxResults = 12)
        if (results.isEmpty()) {
            log.info("[Events]   geen zoekresultaten voor '{}'", cat.id)
            return emptyList()
        }
        val discovered = extractEventsForCategory(username, cat, results)
        log.info("[Events]   AI haalde {} events uit {} resultaten voor '{}'",
            discovered.size, results.size, cat.id)
        return discovered
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
            model = aiModels.modelOrDefault(ExternalCall.ACTION_EVENT_DISCOVERY),
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
    fun discoverSimilar(username: String, preferences: List<String>): List<Event> {
        val today = LocalDate.now()
        val prefList = preferences.joinToString("\n") { "- $it" }
        val ai = openAi.complete(
            model = aiModels.modelOrDefault(ExternalCall.ACTION_EVENT_DISCOVERY),
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
            model = aiModels.modelOrDefault(ExternalCall.ACTION_EVENT_DISCOVERY),
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
            val tree = mapper.readTree(AiJson.extract(text))
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

    /** Normaliseer naar een stabiele dedup-sleutel: lowercase, alfanumeriek + streepjes. */
    private fun normalizeId(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "event-${UUID.randomUUID()}" }
}
