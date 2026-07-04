package com.vdzon.newsfeedbackend.rss.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiJson
import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.rss.PodcastTranscriptLookup
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.infrastructure.ArticleFetcher
import com.vdzon.newsfeedbackend.settings.CategorySettings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Uitgebreide feed-samenvatting: haalt de volledige artikeltekst (of het
 * podcast-transcript) op en bouwt daar met AI een [FeedItem] van.
 * Losgetrokken uit [RssRefreshPipeline] zodat de pipeline een dunne
 * orkestrator blijft.
 */
@Component
class FeedItemGenerator(
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val mapper: ObjectMapper,
    private val articleFetcher: ArticleFetcher,
    private val podcastTranscripts: PodcastTranscriptLookup
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generateFeedItem(username: String, rss: RssItem, categories: List<CategorySettings>): FeedItem {
        // Voor podcast-afleveringen: gebruik het transcript als input
        // i.p.v. de MP3-URL via articleFetcher (die zou falen). Zo krijgt
        // een gepromoveerd podcast-item een rijke uitgebreide samenvatting
        // op basis van wat er ECHT in de aflevering is gezegd, niet
        // alleen de show-notes (AC #4 + #10 van KAN-56).
        val fullText = if (rss.mediaType == "PODCAST") {
            podcastTranscripts.findTranscriptForRssItem(username, rss.id)
                ?: rss.snippet
        } else {
            articleFetcher.fetchPlainText(username, rss.url) ?: rss.snippet
        }
        val catInstr = categories.find { it.id == rss.category }?.extraInstructions.orEmpty()
        val ai = openAi.complete(
            model = aiModels.modelFor(ExternalCall.ACTION_FEED_SUMMARIZE) ?: "gpt-5.4-mini",
            action = ExternalCall.ACTION_FEED_SUMMARIZE,
            username = username,
            subject = rss.title.take(120),
            maxOutputTokens = 4000,
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
            val tree = mapper.readTree(AiJson.extract(ai.text))
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
            createdAt = Instant.now(),
            // KAN-60: propagate de RSS-discriminator naar het feed_item
            // zodat de Feed-tab filter (AC8) op rij-niveau kan filteren.
            mediaType = rss.mediaType,
            imageUrl = rss.imageUrl
        )
    }
}
