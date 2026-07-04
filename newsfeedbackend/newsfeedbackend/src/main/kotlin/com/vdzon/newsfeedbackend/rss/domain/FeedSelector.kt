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

/**
 * AI-selectie voor de persoonlijke feed: beoordeelt welke (samengevatte)
 * RSS-items interessant genoeg zijn, met like/dislike/ster-historie en
 * topic-history als context. Losgetrokken uit [RssRefreshPipeline] zodat
 * de pipeline een dunne orkestrator blijft.
 */
@Component
class FeedSelector(
    private val openAi: OpenAiChatClient,
    private val aiModels: AiModelProperties,
    private val mapper: ObjectMapper,
    private val topicHistory: TopicHistoryRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class SelectionVerdict(val inFeed: Boolean, val reason: String)

    fun selectForFeed(
        username: String,
        items: List<RssItem>,
        categories: List<CategorySettings>,
        existing: List<RssItem>
    ): Map<String, SelectionVerdict> {
        val titles = items.joinToString("\n") {
            // include the AI-generated summary head + topics so Claude has more
            // signal than just title — useful when titles are vague.
            val headline = it.summary.take(180).ifBlank { it.snippet.take(180) }
            val topics = if (it.topics.isNotEmpty()) " [topics: ${it.topics.joinToString(", ")}]" else ""
            "${it.id}|${it.category}|${it.title}$topics\n  → $headline"
        }
        val likedTitles = existing.filter { it.liked == true }.takeLast(20).joinToString("\n") { "+ ${it.title}" }
        val dislikedTitles = existing.filter { it.liked == false }.takeLast(20).joinToString("\n") { "- ${it.title}" }
        val starredTitles = existing.filter { it.starred }.takeLast(10).joinToString("\n") { "* ${it.title}" }
        val recentTopics = topicHistory.load(username)
            .sortedByDescending { (it.likedCount + it.starredCount) * 5 + it.newsCount }
            .take(15)
            .joinToString(", ") { "${it.topic}(news=${it.newsCount},liked=${it.likedCount})" }

        val catContext = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) "\n  voorkeur: ${c.extraInstructions}" else ""
            "${c.id} (${c.name})$instr"
        }

        val ai = openAi.complete(
            model = aiModels.modelOrDefault(ExternalCall.ACTION_FEED_SCORE),
            action = ExternalCall.ACTION_FEED_SCORE,
            username = username,
            subject = "${items.size} kandidaten",
            maxOutputTokens = 16000,
            system = """
                Je bent een nieuwsredacteur die voor een softwareontwikkelaar bepaalt welke artikelen interessant genoeg zijn voor zijn persoonlijke feed.

                Belangrijk:
                - Gebruik de 'voorkeur'-tekst per categorie actief: een artikel dat past bij de voorkeur is in principe relevant.
                - Wees niet te streng. Twijfelgevallen die binnen de gebruikers­voorkeuren vallen mogen worden meegenomen.
                - Schrijf de redenen in het Nederlands, max 1 zin.
                - Beantwoord álle aangeleverde artikelen, in dezelfde volgorde.
                - Antwoord met **alleen** de pure JSON-array, geen markdown-codefences (geen ```), geen prose ervoor of erna.
            """.trimIndent(),
            user = """
                Categorieën en voorkeuren:
                $catContext

                Recent gelezen onderwerpen: ${recentTopics.ifBlank { "(geen)" }}

                Eerder geliket:
                ${likedTitles.ifBlank { "(geen)" }}

                Eerder afgewezen:
                ${dislikedTitles.ifBlank { "(geen)" }}

                Eerder bewaard (sterren):
                ${starredTitles.ifBlank { "(geen)" }}

                Te beoordelen artikelen (id|category|title [topics] → samenvattingsbegin):
                $titles

                Antwoord met een geldig JSON-array (geen prose ervoor of erna). Voor élk id één entry in dezelfde volgorde:
                [{"id": "...", "inFeed": true, "reason": "korte Nederlandse uitleg"}]
                Gebruik inFeed=true voor relevante artikelen, inFeed=false voor de rest.
            """.trimIndent()
        )
        log.debug("[RSS] selectie ruwe AI-output ({} chars): {}", ai.text.length, ai.text.take(2000))
        return try {
            val tree = mapper.readTree(AiJson.extract(ai.text))
            if (!tree.isArray) {
                log.warn("[RSS] selectie: AI gaf geen JSON-array terug — eerste 500 chars: {}", ai.text.take(500))
                return emptyMap()
            }
            val results = mutableMapOf<String, SelectionVerdict>()
            var inFeedCount = 0
            for (node in tree) {
                val id = node.path("id").asText("")
                if (id.isBlank()) continue
                val inFeed = node.path("inFeed").asBoolean(false)
                val reason = node.path("reason").asText("")
                results[id] = SelectionVerdict(inFeed, reason)
                if (inFeed) inFeedCount++
            }
            log.info("[RSS]   selectie response: {} entries beoordeeld ({} inFeed=true, {} inFeed=false)",
                results.size, inFeedCount, results.size - inFeedCount)
            if (inFeedCount == 0 && results.isNotEmpty()) {
                val sampleReasons = results.entries.take(3).joinToString(" | ") { (id, v) ->
                    val title = items.find { it.id == id }?.title?.take(40).orEmpty()
                    "[$title] ${v.reason.take(80)}"
                }
                log.info("[RSS]   AI heeft alle artikelen afgewezen — voorbeelden: {}", sampleReasons)
            }
            results
        } catch (e: Exception) {
            log.warn("[RSS] selectie parse fout: {} — eerste 500 chars: {}", e.message, ai.text.take(500))
            emptyMap()
        }
    }
}
