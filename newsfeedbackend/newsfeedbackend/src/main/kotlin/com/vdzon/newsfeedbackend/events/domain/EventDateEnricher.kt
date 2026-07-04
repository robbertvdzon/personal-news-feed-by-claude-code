package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.ai.AiJson
import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.search.TavilyClient
import com.vdzon.newsfeedbackend.search.TavilyResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Datum-verrijking en -validatie voor discovered events (KAN-68):
 * events zonder valide start_date krijgen één extra Tavily-lookup +
 * lichte AI-extractie; levert die nog steeds niets op dan wordt het
 * event later in de pipeline verworpen.
 */
@Component
class EventDateEnricher(
    private val tavily: TavilyClient,
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** True alleen voor een ISO-8601 YYYY-MM-DD-string die parsebaar is. */
    fun hasValidStartDate(startDate: String?): Boolean {
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
    fun ensureStartDate(username: String, ev: Event): Event {
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
            val tree = mapper.readTree(AiJson.extract(ai.text))
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
}
