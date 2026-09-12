package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.ai.AiClient
import com.vdzon.newsfeedbackend.ai.AiRequest
import com.vdzon.newsfeedbackend.ai.AiResponse
import com.vdzon.newsfeedbackend.ai.SpeechRequest
import com.vdzon.newsfeedbackend.ai.SpeechResponse
import com.vdzon.newsfeedbackend.ai.TranscriptionRequest
import com.vdzon.newsfeedbackend.ai.TranscriptionResponse
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministische in-memory vervanger van de Agent Runtime-client
 * (@Primary in [E2eTestConfig]). Per `action` kan een test een eigen
 * JSON-antwoord scripten; zonder handler geeft [defaultResponse] een
 * plausibel antwoord in het schema dat de betreffende pipeline-stap
 * verwacht, zodat de meeste tests geen scripting nodig hebben.
 */
class FakeAiClient : AiClient {

    data class RecordedCall(
        val action: String,
        val username: String,
        val subject: String?,
        val instruction: String,
        val attachments: Map<String, String>,
        val variant: String?
    ) {
        /** Volledige prompt zoals de agent 'm ziet: opdracht plus tekstbijlagen. */
        val prompt: String get() = instruction + attachments.values.joinToString("\n", prefix = "\n")
    }

    private val json = JsonMapper.builder().build()
    val calls = CopyOnWriteArrayList<RecordedCall>()
    val speechCalls = CopyOnWriteArrayList<SpeechRequest>()
    val transcriptions = CopyOnWriteArrayList<TranscriptionRequest>()
    private val handlers = ConcurrentHashMap<String, (RecordedCall) -> String>()
    @Volatile var speechHandler: (SpeechRequest) -> SpeechResponse = { SpeechResponse(FAKE_AUDIO, AiResponse.STATUS_OK) }
    @Volatile var transcriptionHandler: (TranscriptionRequest) -> TranscriptionResponse = { TranscriptionResponse.Success("Fake transcript.") }

    /** Script een eigen JSON-antwoord voor alle volgende calls met deze action. */
    fun onAction(action: String, handler: (RecordedCall) -> String) {
        handlers[action] = handler
    }

    fun reset() {
        calls.clear(); speechCalls.clear(); transcriptions.clear(); handlers.clear()
        speechHandler = { SpeechResponse(FAKE_AUDIO, AiResponse.STATUS_OK) }
        transcriptionHandler = { TranscriptionResponse.Success("Fake transcript.") }
    }

    fun callsFor(action: String, username: String): List<RecordedCall> =
        calls.filter { it.action == action && it.username == username }

    override fun generate(request: AiRequest): AiResponse {
        val call = RecordedCall(
            request.action, request.username, request.subject, request.instruction,
            request.attachments.associate { it.name to String(it.bytes) }, request.variant
        )
        calls += call
        val text = handlers[request.action]?.invoke(call) ?: defaultResponse(call)
        if (request.cancelled()) return AiResponse(null, AiResponse.STATUS_CANCELLED, "cancelled")
        return try {
            AiResponse(json.readTree(text), AiResponse.STATUS_OK, jobId = "fake-job")
        } catch (e: Exception) {
            AiResponse(null, AiResponse.STATUS_ERROR, "fake: ongeldige JSON: ${e.message}")
        }
    }

    override fun transcribe(request: TranscriptionRequest): TranscriptionResponse {
        transcriptions += request
        return transcriptionHandler(request)
    }

    override fun synthesize(request: SpeechRequest): SpeechResponse {
        speechCalls += request
        return speechHandler(request)
    }

    private fun defaultResponse(call: RecordedCall): String = when (call.action) {
        ExternalCall.ACTION_RSS_SUMMARIZE -> items(ids("### Artikel ", call.prompt)) {
            """{"id": "$it", "summary": "Fake samenvatting van het artikel voor e2e-tests.", "category": "overig", "topics": ["e2e-topic"]}"""
        }

        // Selecteer standaard álle aangeboden artikelen.
        ExternalCall.ACTION_FEED_SCORE -> verdicts(extractCandidateIds(call.prompt), true, "Fake selectie voor e2e-test")

        ExternalCall.ACTION_FEED_SUMMARIZE -> items(ids("## Artikel ", call.prompt)) {
            """{"id": "$it", "titleNl": "Fake NL titel", "shortSummary": "Fake korte samenvatting.", "longSummary": "Fake uitgebreide samenvatting voor het detail-scherm."}"""
        }

        ExternalCall.ACTION_PODCAST_EPISODE_SUMMARIZE ->
            """{"shortSummary": "Fake podcast-samenvatting.", "longSummary": "Fake lange podcast-samenvatting.", "keyTakeaways": ["Fake takeaway 1", "Fake takeaway 2"], "topics": ["podcasts"], "category": "overig"}"""

        ExternalCall.ACTION_DAILY_SUMMARY -> """{"markdown": "Fake dagelijkse samenvatting."}"""
        ExternalCall.ACTION_ADHOC_SUMMARIZE -> """{"items": []}"""
        ExternalCall.ACTION_PODCAST_TRANSLATE -> """{"text": "Fake Nederlandse vertaling."}"""
        ExternalCall.ACTION_PODCAST_SCRIPT ->
            """{"topics": ["AI"], "turns": [{"speaker": "INTERVIEWER", "text": "Welkom."}, {"speaker": "GAST", "text": "Dank je."}]}"""
        else -> "{}"
    }

    companion object {
        val FAKE_AUDIO = "FAKE-MP3-AUDIO".toByteArray()
        private val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        /** Vist de kandidaat-ids ("<uuid>|...") uit een feed_score-prompt. */
        fun extractCandidateIds(prompt: String): List<String> =
            Regex("($UUID)\\|").findAll(prompt).map { it.groupValues[1] }.toList()

        fun verdicts(ids: List<String>, inFeed: Boolean, reason: String): String =
            items(ids, "verdicts") { """{"id": "$it", "inFeed": $inFeed, "reason": "$reason"}""" }

        /** JSON-string escapen voor gebruik in gescripte antwoorden. */
        fun quote(value: String): String = JsonMapper.builder().build().writeValueAsString(value)

        private fun ids(marker: String, prompt: String): List<String> =
            Regex(Regex.escape(marker) + "($UUID)").findAll(prompt).map { it.groupValues[1] }.distinct().toList()

        private fun items(ids: List<String>, field: String = "items", entry: (String) -> String): String =
            ids.joinToString(prefix = """{"$field": [""", postfix = "]}", transform = entry)
    }
}
