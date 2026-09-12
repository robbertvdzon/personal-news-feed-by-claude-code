package com.vdzon.newsfeedbackend.rss.domain

import tools.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiClient
import com.vdzon.newsfeedbackend.ai.AiRequest
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.settings.CategorySettings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * AI-samenvatting van vers opgehaalde RSS-items: korte samenvatting,
 * categorie en onderwerpen. PNF-3: gebatcht — één Agent Runtime-job per
 * [BATCH_SIZE] artikelen in plaats van één call per artikel.
 */
@Component
class RssSummarizer(
    private val ai: AiClient,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Geeft alleen de items terug die daadwerkelijk zijn samengevat. Items
     * uit een mislukte batch worden niet opgeslagen en komen bij de
     * volgende refresh opnieuw langs (dezelfde invoer pakt dan de al
     * gestarte runtime-job op).
     */
    fun summarizeAll(username: String, items: List<RssItem>, categories: List<CategorySettings>): List<RssItem> =
        items.chunked(BATCH_SIZE).flatMapIndexed { index, batch ->
            log.info("[RSS]   samenvatten batch {}/{} ({} artikelen)", index + 1, (items.size + BATCH_SIZE - 1) / BATCH_SIZE, batch.size)
            summarizeBatch(username, batch, categories)
        }

    private fun summarizeBatch(username: String, batch: List<RssItem>, categories: List<CategorySettings>): List<RssItem> {
        val catList = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) " — ${c.extraInstructions.take(200)}" else ""
            "- ${c.id}: ${c.name}$instr"
        }
        val articles = batch.joinToString("\n\n") { rss ->
            """
            ### Artikel ${rss.id}
            Titel: ${rss.title}
            Bron: ${rss.source}
            Snippet: ${rss.snippet.take(2000)}
            """.trimIndent()
        }
        val response = ai.generate(
            AiRequest(
                action = ExternalCall.ACTION_RSS_SUMMARIZE,
                username = username,
                subject = "${batch.size} artikelen: ${batch.first().title.take(80)}",
                instruction = """
                    Je vat nieuwsartikelen kort samen in het Nederlands (150-250 woorden per artikel).
                    Wijs per artikel precies één categorie-id toe op basis van de gebruikersvoorkeuren en extraheer 2-3 onderwerpen.
                    Werk alleen met de aangeleverde gegevens; zoek niets op internet op.

                    Beschikbare categorieën (id, naam, gebruikersinstructies):
                    $catList

                    Geef voor élk artikel hieronder één entry in "items", met het artikel-id exact overgenomen.

                """.trimIndent() + "\n" + articles,
                resultSchema = mapper.readTree(SCHEMA)
            )
        )
        if (!response.ok) {
            log.warn("[RSS] samenvatten mislukt voor {} artikelen: {}", batch.size, response.errorMessage)
            return emptyList()
        }
        val byId = response.result!!.path("items").values().associateBy { it.path("id").asString("") }
        val categoryIds = categories.map { it.id }.toSet()
        return batch.mapNotNull { rss ->
            val node = byId[rss.id] ?: return@mapNotNull null.also { log.warn("[RSS] geen samenvatting teruggekregen voor '{}'", rss.title) }
            val category = node.path("category").asString("overig").ifBlank { "overig" }
            rss.copy(
                summary = node.path("summary").asString(""),
                category = if (categoryIds.isEmpty() || category in categoryIds) category else "overig",
                topics = node.path("topics").values().map { it.asString() }.filter { it.isNotBlank() },
                processedAt = Instant.now()
            )
        }
    }

    companion object {
        const val BATCH_SIZE = 20

        private val SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["items"],"properties":{
              "items":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["id","summary","category","topics"],"properties":{
                "id":{"type":"string"},"summary":{"type":"string"},"category":{"type":"string"},
                "topics":{"type":"array","items":{"type":"string"}}}}}}}
        """.trimIndent()
    }
}
