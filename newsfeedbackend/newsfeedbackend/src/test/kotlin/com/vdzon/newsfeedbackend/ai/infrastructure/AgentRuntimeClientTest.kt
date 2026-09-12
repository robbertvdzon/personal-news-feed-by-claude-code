package com.vdzon.newsfeedbackend.ai.infrastructure

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.vdzon.newsfeedbackend.ai.AiActionProperties
import com.vdzon.newsfeedbackend.ai.AiAttachment
import com.vdzon.newsfeedbackend.ai.AiRequest
import com.vdzon.newsfeedbackend.ai.AiResponse
import com.vdzon.newsfeedbackend.ai.SpeechRequest
import com.vdzon.newsfeedbackend.ai.SpeechSegment
import com.vdzon.newsfeedbackend.ai.TranscriptionRequest
import com.vdzon.newsfeedbackend.ai.TranscriptionResponse
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class AgentRuntimeClientTest {
    private val mapper = JsonMapper.builder().build()
    private val created = CopyOnWriteArrayList<JsonNode>()
    private val uploads = CopyOnWriteArrayList<ByteArray>()
    private val logged = CopyOnWriteArrayList<com.vdzon.newsfeedbackend.external_call.ExternalCall>()
    private val polls = AtomicInteger()
    @Volatile private var jobStatus = "SUCCEEDED"
    @Volatile private var createStatus = 202
    @Volatile private var existingKey: String? = null
    @Volatile private var result = """{"jobId":"job-1","result":{"text":"hallo"},"artifacts":[{"objectId":"out-1","name":"audio"}],"usageSummary":{"metrics":[{"metric":"INPUT_TOKENS","quantity":"10"},{"metric":"OUTPUT_TOKENS","quantity":"5"}],"costs":[{"kind":"API_EQUIVALENT","amount":"0.5","currency":"USD"},{"kind":"CALCULATED","amount":"0.25","currency":"USD"}]}}"""

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v2/") { ex -> handle(ex) }
        start()
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val body = ex.requestBody.readAllBytes()
        fun reply(status: Int, json: String = "", headers: Map<String, String> = emptyMap()) {
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        assertEquals("Bearer test-token", ex.requestHeaders.getFirst("Authorization"))
        when {
            ex.requestMethod == "POST" && path == "/v2/jobs" -> {
                val node = mapper.readTree(body); created.add(node)
                if (createStatus == 409) { existingKey = node.path("idempotencyKey").asString(); reply(409, """{"code":"IDEMPOTENCY_CONFLICT"}""") }
                else reply(202, """{"id":"job-1","status":"QUEUED"}""")
            }
            ex.requestMethod == "GET" && path == "/v2/jobs" -> reply(200, """{"items":[{"id":"job-existing","idempotencyKey":"${existingKey ?: "other"}"}]}""")
            ex.requestMethod == "GET" && path.endsWith("/result") -> reply(200, result)
            ex.requestMethod == "GET" && path.endsWith("/content") -> reply(200, "MP3")
            ex.requestMethod == "GET" && path.startsWith("/v2/jobs/") ->
                reply(200, if (polls.incrementAndGet() < 2) """{"status":"RUNNING"}""" else """{"status":"$jobStatus","errorCode":"ENGINE_FAILED","errorMessage":"kapot"}""")
            ex.requestMethod == "POST" && path == "/v2/uploads" -> reply(201, """{"uploadId":"up-1","chunkSizeBytes":4,"offset":0}""")
            ex.requestMethod == "PATCH" -> { uploads += body; reply(204, headers = mapOf("Upload-Offset" to (ex.requestHeaders.getFirst("Upload-Offset").toLong() + body.size).toString())) }
            ex.requestMethod == "POST" && path.endsWith("/complete") -> reply(200, """{"objectId":"11111111-1111-1111-1111-111111111111"}""")
            else -> reply(404)
        }
    }

    private val client = AgentRuntimeClient(
        "http://127.0.0.1:${server.address.port}", "test-token", "test", 1, 1,
        AiActionProperties().apply {
            actions[ExternalCall.ACTION_RSS_SUMMARIZE] = "anthropic/claude-sonnet-5/SUBSCRIPTION"
            actions[ExternalCall.ACTION_PODCAST_TRANSCRIBE] = "local/large-v3-turbo/LOCAL"
            actions[ExternalCall.ACTION_PODCAST_TTS] = "openai/tts-1/API"
        },
        mapper,
        object : ExternalCallLogger { override fun log(call: com.vdzon.newsfeedbackend.external_call.ExternalCall) { logged += call } }
    )

    @AfterEach
    fun stop() = server.stop(0)

    private fun request(vararg attachments: AiAttachment) = AiRequest(
        ExternalCall.ACTION_RSS_SUMMARIZE, "robbert", "test", "Vat samen.",
        mapper.readTree("""{"type":"object"}"""), attachments.toList()
    )

    @Test
    fun `structured job wordt aangemaakt, gepolld en het resultaat plus werkelijke kosten gelogd`() {
        val response = client.generate(request())

        assertTrue(response.ok)
        assertEquals("hallo", response.result!!.path("text").asString())
        val job = created.single()
        assertEquals("STRUCTURED_GENERATION", job.path("taskType").asString())
        assertEquals("anthropic", job.path("execution").path("vendorId").asString())
        assertEquals("SUBSCRIPTION", job.path("execution").path("mode").asString())
        assertTrue(job.path("idempotencyKey").asString().startsWith("pnf:test:rss_summarize:"))
        val log = logged.single()
        assertEquals(ExternalCall.PROVIDER_AGENT_RUNTIME, log.provider)
        assertEquals(10L, log.tokensIn)
        assertEquals(0.25, log.costUsd, 0.0001)
    }

    @Test
    fun `dezelfde invoer geeft dezelfde idempotency-key, een variant een nieuwe`() {
        client.generate(request()); client.generate(request()); client.generate(request().copy(variant = "rerun"))
        val keys = created.map { it.path("idempotencyKey").asString() }
        assertEquals(keys[0], keys[1])
        assertTrue(keys[0] != keys[2])
    }

    @Test
    fun `bijlagen gaan via resumable upload en een idempotency-conflict pakt de bestaande job op`() {
        createStatus = 409
        val response = client.generate(request(AiAttachment.text("articles", "0123456789")))

        assertTrue(response.ok)
        assertEquals("job-existing", response.jobId)
        assertEquals("0123456789", uploads.joinToString("") { String(it) })
        assertEquals("articles", created.single().path("input").path("objects").values().single().path("name").asString())
    }

    @Test
    fun `mislukte job geeft een error-response zonder exception`() {
        jobStatus = "FAILED"
        val response = client.generate(request())
        assertEquals(AiResponse.STATUS_ERROR, response.status)
        assertTrue(response.errorMessage!!.contains("ENGINE_FAILED"))
    }

    @Test
    fun `transcriptie uploadt audio als LOCAL-job en leest de tekst uit het resultaat`(@TempDir dir: Path) {
        val audio = dir.resolve("a.mp3").toFile().apply { writeBytes("AUDIO".toByteArray()) }
        val response = client.transcribe(TranscriptionRequest("robbert", "ep", audio))

        assertEquals(TranscriptionResponse.Success("hallo"), response)
        val job = created.single()
        assertEquals("TRANSCRIPTION", job.path("taskType").asString())
        assertEquals("AUDIO", job.path("input").path("objects").values().single().path("role").asString())
        assertEquals("transcript", job.path("output").path("artifacts").values().single().path("name").asString())
    }

    @Test
    fun `tts stuurt de segmenten als json en downloadt de audio`() {
        val response = client.synthesize(SpeechRequest(ExternalCall.ACTION_PODCAST_TTS, "robbert", "p", listOf(SpeechSegment("Hoi", "onyx", 1.2), SpeechSegment("Dag", "alloy"))))

        assertTrue(response.ok)
        assertArrayEquals("MP3".toByteArray(), response.audio)
        val segments = mapper.readTree(uploads.joinToString("") { String(it) }).path("segments").values()
        assertEquals(listOf("onyx", "alloy"), segments.map { it.path("voice").asString() })
        assertEquals("SPEECH_SYNTHESIS", created.single().path("taskType").asString())
        assertEquals("onyx", created.single().path("synthesis").path("voice").asString())
    }
}
