package com.vdzon.newsfeedbackend.podcast_source.domain

import tools.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AiAttachment
import com.vdzon.newsfeedbackend.ai.AiClient
import com.vdzon.newsfeedbackend.ai.AiRequest
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
    private val ai: AiClient,
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

        private val SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["shortSummary","longSummary","keyTakeaways","topics","category"],"properties":{
              "shortSummary":{"type":"string"},"longSummary":{"type":"string"},
              "keyTakeaways":{"type":"array","items":{"type":"string"}},
              "topics":{"type":"array","items":{"type":"string"}},"category":{"type":"string"}}}
        """.trimIndent()
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
        val response = ai.generate(
            AiRequest(
                action = episodeAction,
                username = username,
                subject = "Podcast '${ep.podcastName.take(40)}' — ${ep.title.take(80)}",
                resultSchema = mapper.readTree(SCHEMA),
                attachments = listOf(AiAttachment.text("episode-input", sample, "text/plain")),
                instruction = """
                    Je vat een podcast-aflevering samen in het Nederlands. De input (transcript of show-notes; mogelijk afgekapt) staat in het invoerobject 'episode-input' (/job/input/objects/episode-input/content).
                    Werk alleen met die input; zoek niets op internet op.

                    Podcast: ${ep.podcastName}
                    Aflevering: ${ep.title}
                    ${if (!ep.publishedDate.isNullOrBlank()) "Datum: ${ep.publishedDate}" else ""}
                    ${if ((ep.durationSeconds ?: 0) > 0) "Duur: ${(ep.durationSeconds ?: 0) / 60} min" else ""}

                    shortSummary: 1-2 zinnen (~30-50 woorden, plain text — geen markdown) die in 1 oogopslag duidelijk maken waar deze aflevering over gaat. Eindig met een punt.

                    longSummary: 3-5 alinea's plain-text Nederlands (~400-600 woorden) die gestructureerd beschrijven wat in de aflevering wordt besproken — chronologisch of thematisch. Reflecteer concrete inhoud uit het transcript (namen van tools/frameworks/personen, citaten, voorbeelden) i.p.v. marketing-platitudes. Scheid alinea's met een lege regel. Géén markdown-headers, géén bullet-list — gewone prose. Bij korte input (b.v. show-notes van <500 woorden) mag het korter — 2-3 alinea's volstaat dan, geen opgeklopte vulling.

                    keyTakeaways: 5-10 concrete takeaways/inzichten. Eén bullet per item, max ~20 woorden, géén sub-bullets, géén markdown. Voor tech-podcasts: noem tools/concepts/frameworks in de bullet zelf. Bij hele korte input mag de lijst korter (3-4 takeaways). Niet alleen "ze bespraken X" — schrijf wat erover gezegd is.

                    topics: 3-8 korte Nederlandse onderwerpen die in de aflevering aan bod zijn gekomen. Pak concrete inhoudelijke topics, geen marketing-woorden.

                    category: kies één id uit de gebruikersvoorkeuren hieronder (fallback "overig").
                    Beschikbare categorieën (id, naam, voorkeur):
                """.trimIndent() + "\n" + catList
            )
        )
        if (!response.ok) {
            log.warn("[PodcastEpisode] samenvatting mislukt voor guid={}: {}", ep.guid, response.errorMessage)
            return null
        }
        val tree = response.result!!
        val shortSum = tree.path("shortSummary").asString("").trim()
        if (shortSum.isBlank()) {
            log.warn("[PodcastEpisode] AI gaf geen shortSummary voor guid={}", ep.guid)
            return null
        }
        return Summarized(
            shortSummary = shortSum,
            category = tree.path("category").asString("overig").ifBlank { "overig" },
            topics = tree.path("topics").values().mapNotNull { it.asString().takeUnless { t -> t.isBlank() } },
            longSummary = tree.path("longSummary").asString("").trim(),
            keyTakeaways = tree.path("keyTakeaways").values().mapNotNull { it.asString().takeUnless { t -> t.isBlank() } }.map { it.trim() }
        )
    }
}
