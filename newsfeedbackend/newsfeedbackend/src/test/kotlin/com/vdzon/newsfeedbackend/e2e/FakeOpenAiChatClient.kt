package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import com.vdzon.newsfeedbackend.ai.OpenAiChatResponse
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministische in-memory vervanger van de OpenAI-client (@Primary in
 * [E2eTestConfig]). Per `action` kan een test een eigen handler zetten;
 * zonder handler geeft [defaultResponse] een plausibel JSON-antwoord in
 * het formaat dat de betreffende pipeline-stap verwacht, zodat de meeste
 * tests geen scripting nodig hebben.
 */
class FakeOpenAiChatClient : OpenAiChatClient {

    data class RecordedCall(
        val model: String,
        val action: String,
        val username: String,
        val system: String,
        val user: String,
        val subject: String?
    )

    val calls = CopyOnWriteArrayList<RecordedCall>()
    private val handlers = ConcurrentHashMap<String, (RecordedCall) -> String>()

    /** Script een eigen antwoord voor alle volgende calls met deze action. */
    fun onAction(action: String, handler: (RecordedCall) -> String) {
        handlers[action] = handler
    }

    fun reset() {
        calls.clear()
        handlers.clear()
    }

    fun callsFor(action: String, username: String): List<RecordedCall> =
        calls.filter { it.action == action && it.username == username }

    override fun complete(
        action: String, username: String, system: String, user: String,
        subject: String?, maxOutputTokens: Int
    ): OpenAiChatResponse = respond("fake-translate-model", action, username, system, user, subject)

    override fun complete(
        model: String, action: String, username: String, system: String, user: String,
        subject: String?, maxOutputTokens: Int
    ): OpenAiChatResponse = respond(model, action, username, system, user, subject)

    override fun completeJson(
        model: String, schemaName: String, schema: String, action: String, username: String,
        system: String, user: String, subject: String?, maxOutputTokens: Int
    ): OpenAiChatResponse = respond(model, action, username, system, user, subject)

    override fun translateModel(): String = "fake-translate-model"

    private fun respond(
        model: String, action: String, username: String,
        system: String, user: String, subject: String?
    ): OpenAiChatResponse {
        val call = RecordedCall(model, action, username, system, user, subject)
        calls += call
        val text = handlers[action]?.invoke(call) ?: defaultResponse(call)
        return OpenAiChatResponse(
            text = text,
            inputTokens = 100,
            outputTokens = 50,
            costUsd = 0.0,
            model = model,
            status = "ok"
        )
    }

    private fun defaultResponse(call: RecordedCall): String = when (call.action) {
        ExternalCall.ACTION_RSS_SUMMARIZE ->
            """{"summary": "Fake samenvatting van het artikel voor e2e-tests.", "category": "overig", "topics": ["e2e-topic"]}"""

        // Selecteer standaard álle aangeboden artikelen: echo de ids die in
        // de prompt staan (in het formaat "<uuid>|<category>|<title>" —
        // géén regelbegin-anker, want de eerste regel kan ingesprongen zijn
        // door de trimIndent-interpolatie in de pipeline-prompt).
        ExternalCall.ACTION_FEED_SCORE -> {
            val ids = extractCandidateIds(call.user)
            ids.joinToString(prefix = "[", postfix = "]") {
                """{"id": "$it", "inFeed": true, "reason": "Fake selectie voor e2e-test"}"""
            }
        }

        ExternalCall.ACTION_FEED_SUMMARIZE ->
            """{"titleNl": "Fake NL titel", "shortSummary": "Fake korte samenvatting.", "longSummary": "Fake uitgebreide samenvatting voor het detail-scherm."}"""

        ExternalCall.ACTION_PODCAST_EPISODE_SUMMARIZE ->
            """{"shortSummary": "Fake podcast-samenvatting.", "longSummary": "Fake lange podcast-samenvatting.", "keyTakeaways": ["Fake takeaway 1", "Fake takeaway 2"], "topics": ["podcasts"], "category": "overig"}"""

        else -> "{}"
    }

    companion object {
        /** Vist de kandidaat-ids ("<uuid>|...") uit een feed_score-prompt. */
        fun extractCandidateIds(prompt: String): List<String> =
            Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\|")
                .findAll(prompt).map { it.groupValues[1] }.toList()
    }
}
