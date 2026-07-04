package com.vdzon.newsfeedbackend.podcast_source.domain

import tools.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiJson
import com.vdzon.newsfeedbackend.ai.AiModelProperties
import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * KAN-60/KAN-62: Claude-samenvatting van één podcast-aflevering.
 *
 * Gedeeld door beide pipeline-fasen: fase 1 voert de RSS-show-notes in
 * ([PodcastShowNotesProcessor]), fase 2 het Whisper-transcript
 * ([PodcastTranscriptProcessor]). Dezelfde prompt en char-limiet werken
 * voor beide inputs (zie [MAX_CLAUDE_INPUT_CHARS]).
 */
@Component
class PodcastEpisodeSummarizer(
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val settings: SettingsService,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * KAN-62: max aantal transcript-chars dat we naar Claude sturen.
         * 80k is genoeg voor ~70-min podcasts (Latent Space, Lex Fridman)
         * en past ruim binnen Sonnet's 200k context-window. Bestond
         * eerder als 12k char-afkap — dat was te krap voor de lange
         * samenvatting (90-min podcasts ≈ 60-90k chars). Show-notes-
         * input is bijna altijd <5k dus dezelfde limiet werkt voor
         * beide fasen zonder aparte logica.
         */
        internal const val MAX_CLAUDE_INPUT_CHARS = 80_000
    }

    internal data class Summarized(
        val shortSummary: String,
        val category: String,
        val topics: List<String>,
        /**
         * KAN-62: 400-600 woorden NL-prose in 3-5 alinea's voor het
         * detail-scherm. Blank-string acceptabel (b.v. zeer korte
         * show-notes-input) — frontend valt dan terug op shortSummary.
         */
        val longSummary: String,
        /**
         * KAN-62: 5-10 bullets van max 1 regel. Lege lijst acceptabel
         * voor heel korte input.
         */
        val keyTakeaways: List<String>
    )

    /**
     * Eén Claude-call die zowel de korte card-samenvatting als de
     * uitgebreidere detail-velden (longSummary + keyTakeaways) levert.
     * Eén round-trip i.p.v. twee om de latency op het kritieke pad
     * (show-notes-fase) niet te verdubbelen.
     */
    internal fun summarize(username: String, ep: PodcastEpisode, input: String): Summarized? {
        val categories = settings.getCategories(username).filter { it.enabled || it.isSystem }
        val catList = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) " — ${c.extraInstructions.take(200)}" else ""
            "- ${c.id}: ${c.name}$instr"
        }
        // KAN-62: Whisper-transcripts van 60-90 min lopen tot ~70k chars.
        // We sturen alles tot 80k naar Claude zodat de lange samenvatting
        // het inhoudelijk verloop van de aflevering reflecteert i.p.v.
        // alleen de opening (zoals de oude 12k afkap). Bij langere
        // transcripts kappen we eerlijk af; 80k past ruim binnen de
        // 200k context van Sonnet en haalt nog steeds 99% van de
        // beschikbare podcasts compleet binnen.
        val sample = if (input.length > MAX_CLAUDE_INPUT_CHARS) {
            input.take(MAX_CLAUDE_INPUT_CHARS) + "\n[...afgekort wegens lengte...]"
        } else {
            input
        }
        val episodeAction = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_PODCAST_EPISODE_SUMMARIZE
        val ai = openAi.complete(
            model = aiModels.modelOrDefault(episodeAction),
            action = episodeAction,
            username = username,
            subject = "Podcast '${ep.podcastName.take(40)}' — ${ep.title.take(80)}",
            maxOutputTokens = 4096,
            system = """
                Je vat podcast-afleveringen samen in het Nederlands.

                shortSummary: 1-2 zinnen (~30-50 woorden, plain text — geen markdown) die in 1 oogopslag duidelijk maken waar deze aflevering over gaat. Eindig met een punt.

                longSummary: 3-5 alinea's plain-text Nederlands (~400-600 woorden) die gestructureerd beschrijven wat in de aflevering wordt besproken — chronologisch of thematisch. Reflecteer concrete inhoud uit het transcript (namen van tools/frameworks/personen, citaten, voorbeelden) i.p.v. marketing-platitudes. Scheid alinea's met een lege regel. Géén markdown-headers, géén bullet-list — gewone prose. Bij korte input (b.v. show-notes van <500 woorden) mag het korter — 2-3 alinea's volstaat dan, geen opgeklopte vulling.

                keyTakeaways: 5-10 concrete takeaways/inzichten als JSON-array van strings. Eén bullet per regel, max ~20 woorden, géén sub-bullets, géén markdown-headers. Voor tech-podcasts: noem tools/concepts/frameworks in de bullet zelf. Bij hele korte input mag de lijst korter (3-4 takeaways). Niet alleen "ze bespraken X" — schrijf wat erover gezegd is.

                topics: 3-8 korte Nederlandse onderwerpen die in de aflevering aan bod zijn gekomen. Pak concrete inhoudelijke topics, geen marketing-woorden.

                category: kies één id uit de gebruikersvoorkeuren hieronder (fallback "overig").

                Antwoord uitsluitend met geldig JSON, geen markdown-codefences (geen ```), geen prose ervoor of erna.
            """.trimIndent(),
            user = buildString {
                appendLine("Podcast: ${ep.podcastName}")
                appendLine("Aflevering: ${ep.title}")
                if (!ep.publishedDate.isNullOrBlank()) appendLine("Datum: ${ep.publishedDate}")
                if ((ep.durationSeconds ?: 0) > 0) appendLine("Duur: ${(ep.durationSeconds ?: 0) / 60} min")
                appendLine()
                appendLine("Beschikbare categorieën (id, naam, voorkeur):")
                appendLine(catList)
                appendLine()
                appendLine("Input (transcript of show-notes; mogelijk afgekapt):")
                appendLine(sample)
                appendLine()
                appendLine("Antwoord met JSON in dit schema:")
                append("""{"shortSummary": "...", "longSummary": "...", "keyTakeaways": ["...", "..."], "topics": ["..."], "category": "kotlin"}""")
            }
        )
        val raw = ai.text.trim()
        if (raw.isBlank()) {
            log.warn("[PodcastEpisode] Claude gaf lege response voor guid={}", ep.guid)
            return null
        }
        return try {
            val tree = mapper.readTree(AiJson.extract(raw))
            val shortSum = tree.path("shortSummary").asText("").trim()
            val cat = tree.path("category").asText("overig").ifBlank { "overig" }
            val topics = tree.path("topics").mapNotNull { it.asText().takeUnless { t -> t.isBlank() } }
            val longSum = tree.path("longSummary").asText("").trim()
            val takeaways = tree.path("keyTakeaways")
                .mapNotNull { it.asText().takeUnless { t -> t.isBlank() } }
                .map { it.trim() }
            if (shortSum.isBlank()) {
                log.warn("[PodcastEpisode] Claude gaf geen shortSummary voor guid={}", ep.guid)
                return null
            }
            Summarized(
                shortSummary = shortSum,
                category = cat,
                topics = topics,
                longSummary = longSum,
                keyTakeaways = takeaways
            )
        } catch (e: Exception) {
            log.warn("[PodcastEpisode] parse-fout in Claude-response voor guid={}: {} — head: {}",
                ep.guid, e.message, raw.take(300))
            null
        }
    }
}
