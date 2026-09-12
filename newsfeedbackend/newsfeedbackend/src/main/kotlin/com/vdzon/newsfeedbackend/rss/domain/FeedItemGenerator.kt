package com.vdzon.newsfeedbackend.rss.domain

import tools.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiAttachment
import com.vdzon.newsfeedbackend.ai.AiClient
import com.vdzon.newsfeedbackend.ai.AiRequest
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.rss.PodcastTranscriptLookup
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.infrastructure.ArticleFetcher
import com.vdzon.newsfeedbackend.settings.CategorySettings
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Uitgebreide feed-samenvatting: haalt de volledige artikeltekst (of het
 * podcast-transcript) op en bouwt daar met AI een [FeedItem] van.
 * PNF-3: gebatcht via de Agent Runtime; de artikelteksten gaan als
 * bijlage mee zodat de opdracht compact blijft.
 */
@org.springframework.stereotype.Component
class FeedItemGenerator(
    private val ai: AiClient,
    private val mapper: ObjectMapper,
    private val articleFetcher: ArticleFetcher,
    private val podcastTranscripts: PodcastTranscriptLookup
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generateFeedItem(username: String, rss: RssItem, categories: List<CategorySettings>): FeedItem =
        generateFeedItems(username, listOf(rss), categories).getValue(rss.id)

    /**
     * Levert voor élk item een [FeedItem] (id → item). Mislukt de AI-stap,
     * dan valt een item terug op de korte RSS-samenvatting zodat de feed
     * niet leeg blijft.
     */
    fun generateFeedItems(username: String, items: List<RssItem>, categories: List<CategorySettings>): Map<String, FeedItem> =
        items.chunked(BATCH_SIZE).flatMap { batch -> generateBatch(username, batch, categories).entries }.associate { it.key to it.value }

    private fun generateBatch(username: String, batch: List<RssItem>, categories: List<CategorySettings>): Map<String, FeedItem> {
        val articles = buildString {
            for (rss in batch) {
                // Voor podcast-afleveringen: gebruik het transcript als input
                // i.p.v. de MP3-URL via articleFetcher (die zou falen).
                val fullText = if (rss.mediaType == "PODCAST") {
                    podcastTranscripts.findTranscriptForRssItem(username, rss.id) ?: rss.snippet
                } else {
                    articleFetcher.fetchPlainText(username, rss.url) ?: rss.snippet
                }
                val catInstr = categories.find { it.id == rss.category }?.extraInstructions.orEmpty()
                appendLine("## Artikel ${rss.id}")
                appendLine("Originele titel: ${rss.title}")
                appendLine("Bron: ${rss.source}")
                appendLine("URL: ${rss.url}")
                if (catInstr.isNotBlank()) appendLine("Lezerscontext (categorie '${rss.category}'): $catInstr")
                appendLine()
                appendLine("Volledige artikeltekst (mogelijk afgekort):")
                appendLine(fullText.take(MAX_ARTICLE_CHARS))
                appendLine()
            }
        }
        val response = ai.generate(
            AiRequest(
                action = ExternalCall.ACTION_FEED_SUMMARIZE,
                username = username,
                subject = "${batch.size} feed-items: ${batch.first().title.take(80)}",
                resultSchema = mapper.readTree(SCHEMA),
                attachments = listOf(AiAttachment.text("articles", articles)),
                instruction = """
                    Je schrijft voor een persoonlijk nieuwsoverzicht in het Nederlands drie velden per artikel.
                    De artikelen staan in het invoerobject 'articles' (/job/input/objects/articles/content), elk onder een kop "## Artikel <id>".
                    Werk alleen met die tekst; zoek niets op internet op.

                    titleNl: Korte beschrijvende titel (max 70 tekens) die in één oogopslag duidelijk maakt waar het artikel over gaat. Géén opsmuk, geen punten aan het eind, geen quotes.
                    shortSummary: Twee regels Nederlandse samenvatting (~30-50 woorden, plain text — GÉÉN markdown). Vat de kern van het nieuws samen zoals een teaser onder een krantenkop. Eindig met een punt.
                    longSummary: Uitgebreide journalistieke samenvatting van 400-600 woorden in het Nederlands. Geef context, betekenis en relevantie. Gebruik géén markdown-headers (`#`), maar **vet** voor begrippen en aparte paragrafen mogen.

                    Geef voor élk artikel één entry in "items" met het artikel-id exact overgenomen. Artikel-ids: ${batch.joinToString(", ") { it.id }}
                """.trimIndent()
            )
        )
        if (!response.ok) log.warn("[RSS] feed-items genereren mislukt ({} items): {} — fallback op RSS-samenvatting", batch.size, response.errorMessage)
        val byId = response.result?.path("items")?.values()?.associateBy { it.path("id").asString("") }.orEmpty()
        return batch.associate { rss ->
            val node = byId[rss.id]
            var titleNl = node?.path("titleNl")?.asString("")?.trim().orEmpty()
            var shortSummary = node?.path("shortSummary")?.asString("")?.trim().orEmpty()
            var longSummary = node?.path("longSummary")?.asString("")?.trim().orEmpty()
            // Fallback-keten zodat een mislukte AI-stap de feed niet leeg laat:
            if (longSummary.isBlank()) longSummary = rss.summary.ifBlank { rss.snippet }
            if (shortSummary.isBlank()) shortSummary = rss.summary.take(200).ifBlank { rss.snippet.take(200) }
            if (titleNl.isBlank()) titleNl = rss.title
            rss.id to FeedItem(
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
                createdAt = Instant.now(),
                // KAN-60: propagate de RSS-discriminator naar het feed_item
                // zodat de Feed-tab filter (AC8) op rij-niveau kan filteren.
                mediaType = rss.mediaType,
                imageUrl = rss.imageUrl
            )
        }
    }

    companion object {
        const val BATCH_SIZE = 8
        const val MAX_ARTICLE_CHARS = 12_000

        private val SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["items"],"properties":{
              "items":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["id","titleNl","shortSummary","longSummary"],"properties":{
                "id":{"type":"string"},"titleNl":{"type":"string"},"shortSummary":{"type":"string"},"longSummary":{"type":"string"}}}}}}
        """.trimIndent()
    }
}
