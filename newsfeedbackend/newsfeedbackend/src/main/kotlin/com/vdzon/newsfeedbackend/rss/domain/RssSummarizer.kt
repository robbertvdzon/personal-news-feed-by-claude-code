package com.vdzon.newsfeedbackend.rss.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiJson
import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.settings.CategorySettings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * AI-samenvatting per artikel: vat een vers opgehaald RSS-item kort samen,
 * wijst een categorie toe en extraheert onderwerpen. Losgetrokken uit
 * [RssRefreshPipeline] zodat de pipeline een dunne orkestrator blijft.
 */
@Component
class RssSummarizer(
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun summarize(username: String, rss: RssItem, categories: List<CategorySettings>): RssItem? {
        val catList = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) " — ${c.extraInstructions.take(200)}" else ""
            "- ${c.id}: ${c.name}$instr"
        }
        val ai = openAi.complete(
            model = aiModels.modelFor(ExternalCall.ACTION_RSS_SUMMARIZE) ?: "gpt-5.4-mini",
            action = ExternalCall.ACTION_RSS_SUMMARIZE,
            username = username,
            subject = rss.title.take(120),
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
            val tree = mapper.readTree(AiJson.extract(ai.text))
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
}
