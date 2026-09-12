package com.vdzon.newsfeedbackend.ai.infrastructure

import com.vdzon.newsfeedbackend.ai.AiActionProperties
import com.vdzon.newsfeedbackend.ai.AiAttachment
import com.vdzon.newsfeedbackend.ai.AiClient
import com.vdzon.newsfeedbackend.ai.AiExecution
import com.vdzon.newsfeedbackend.ai.AiRequest
import com.vdzon.newsfeedbackend.ai.AiResponse
import com.vdzon.newsfeedbackend.ai.SpeechRequest
import com.vdzon.newsfeedbackend.ai.SpeechResponse
import com.vdzon.newsfeedbackend.ai.TranscriptionRequest
import com.vdzon.newsfeedbackend.ai.TranscriptionResponse
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * PNF-3: [AiClient] op de Agent Runtime v2 API.
 *
 * Elke aanroep wordt één job: eventuele bijlagen gaan via resumable
 * uploads, daarna `POST /v2/jobs` en pollen tot een eindstatus. De
 * idempotency-key is een hash over uitvoering, schema, instructie en
 * bijlagen: een herstart of een volgende run met dezelfde invoer pakt de
 * bestaande (al betaalde of nog lopende) job op in plaats van een nieuwe
 * te starten. Dat maakt blokkerend wachten veilig — ook als de worker
 * (laptop) even offline is, gaat er geen werk verloren.
 */
@Component
class AgentRuntimeClient(
    @param:Value("\${app.agent-runtime.base-url:https://agent-runtime.vdzonsoftware.nl}") private val baseUrl: String,
    @param:Value("\${app.agent-runtime.token:}") private val token: String,
    @param:Value("\${app.agent-runtime.environment:prod}") private val environment: String,
    @param:Value("\${app.agent-runtime.poll-interval-ms:2000}") private val pollIntervalMs: Long,
    @param:Value("\${app.agent-runtime.max-wait-minutes:120}") private val maxWaitMinutes: Long,
    private val actions: AiActionProperties,
    private val mapper: ObjectMapper,
    private val callLogger: ExternalCallLogger
) : AiClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    override fun generate(request: AiRequest): AiResponse {
        val started = Instant.now()
        val execution = actions.execution(request.action)
        val attachments = request.attachments.toMutableList()
        val instruction = if (request.instruction.length > MAX_INLINE_INSTRUCTION) {
            attachments += AiAttachment.text("prompt", request.instruction)
            "Lees de volledige opdracht in het invoerobject 'prompt' (/job/input/objects/prompt/content) en voer die exact uit."
        } else request.instruction
        return try {
            val job = submit(request.action, execution, "STRUCTURED_GENERATION", instruction, request.resultSchema, attachments, emptyList(), request.variant, null, null)
            val outcome = await(job, request.cancelled)
            when (outcome.status) {
                "SUCCEEDED" -> {
                    val result = getJson("/v2/jobs/$job/result")
                    logCall(request.action, request.username, started, request.subject, execution, result, "ok", null)
                    AiResponse(result.path("result"), AiResponse.STATUS_OK, jobId = job)
                }
                "CANCELLED" -> {
                    logCall(request.action, request.username, started, request.subject, execution, null, "error", "cancelled")
                    AiResponse(null, if (request.cancelled()) AiResponse.STATUS_CANCELLED else AiResponse.STATUS_ERROR, outcome.message, job)
                }
                else -> {
                    logCall(request.action, request.username, started, request.subject, execution, null, "error", outcome.message)
                    AiResponse(null, AiResponse.STATUS_ERROR, outcome.message, job)
                }
            }
        } catch (e: Exception) {
            log.warn("[AgentRuntime] {} faalde: {}", request.action, e.message)
            logCall(request.action, request.username, started, request.subject, execution, null, "error", e.message)
            AiResponse(null, AiResponse.STATUS_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun transcribe(request: TranscriptionRequest): TranscriptionResponse {
        val started = Instant.now()
        val action = ExternalCall.ACTION_PODCAST_TRANSCRIBE
        val execution = actions.execution(action)
        return try {
            val audio = AttachmentSource.file("audio", "episode.mp3", request.mimeType, request.audio)
            val artifacts = listOf(mapOf("name" to "transcript", "required" to true, "mimeTypes" to listOf("text/plain")))
            val transcription = request.language?.let { mapOf("language" to it) }
            val job = submitSources(action, execution, "TRANSCRIPTION", "Transcribeer de audio in het invoerobject.", null, listOf(audio), artifacts, null, transcription, null, role = "AUDIO")
            val outcome = await(job) { false }
            if (outcome.status != "SUCCEEDED") {
                logCall(action, request.username, started, request.subject, execution, null, "error", outcome.message)
                return if (outcome.errorCode in FATAL_TRANSCRIPTION_CODES) TranscriptionResponse.Fatal(outcome.message)
                else TranscriptionResponse.Retryable(outcome.message)
            }
            val result = getJson("/v2/jobs/$job/result")
            logCall(action, request.username, started, request.subject, execution, result, "ok", null)
            val text = result.path("result").path("text").asString("")
            if (text.isBlank()) TranscriptionResponse.Fatal("Runtime gaf een leeg transcript") else TranscriptionResponse.Success(text)
        } catch (e: Exception) {
            log.warn("[AgentRuntime] transcriptie faalde: {}", e.message)
            logCall(action, request.username, started, request.subject, execution, null, "error", e.message)
            TranscriptionResponse.Retryable(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun synthesize(request: SpeechRequest): SpeechResponse {
        val started = Instant.now()
        val execution = actions.execution(request.action)
        return try {
            val segments = mapper.createObjectNode().also { root ->
                val array = root.putArray("segments")
                request.segments.filter { it.text.isNotBlank() }.forEach { segment ->
                    array.addObject().put("text", segment.text).put("voice", segment.voice).also { node -> segment.speed?.let { node.put("speed", it) } }
                }
            }
            val text = AiAttachment("text", "segments.json", "application/json", mapper.writeValueAsBytes(segments))
            val artifacts = listOf(mapOf("name" to "audio", "required" to true, "mimeTypes" to listOf("audio/mpeg")))
            val synthesis = mapOf("voice" to request.segments.first().voice)
            val job = submit(request.action, execution, "SPEECH_SYNTHESIS", "Zet de tekstsegmenten om naar spraak.", null, listOf(text), artifacts, request.variant, null, synthesis)
            val outcome = await(job) { false }
            if (outcome.status != "SUCCEEDED") {
                logCall(request.action, request.username, started, request.subject, execution, null, "error", outcome.message)
                return SpeechResponse(null, AiResponse.STATUS_ERROR, outcome.message)
            }
            val result = getJson("/v2/jobs/$job/result")
            val objectId = result.path("artifacts").values().firstOrNull { it.path("name").asString("") == "audio" }?.path("objectId")?.asString(null)
                ?: throw IOException("Runtime-resultaat bevat geen audio-artifact")
            val bytes = download("/v2/jobs/$job/objects/$objectId/content")
            logCall(request.action, request.username, started, request.subject, execution, result, "ok", null, ExternalCall.UNIT_CHARACTERS)
            SpeechResponse(bytes, AiResponse.STATUS_OK)
        } catch (e: Exception) {
            log.warn("[AgentRuntime] TTS {} faalde: {}", request.action, e.message)
            logCall(request.action, request.username, started, request.subject, execution, null, "error", e.message, ExternalCall.UNIT_CHARACTERS)
            SpeechResponse(null, AiResponse.STATUS_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private data class Outcome(val status: String, val message: String, val errorCode: String?)

    /** Een bijlage die pas bij uploaden wordt gelezen, zodat grote audiobestanden niet in het geheugen staan. */
    private class AttachmentSource(val name: String, val filename: String, val mimeType: String, val size: Long, val sha256: String, val open: () -> InputStream) {
        companion object {
            fun bytes(a: AiAttachment) = AttachmentSource(a.name, a.filename, a.mimeType, a.bytes.size.toLong(), sha256(a.bytes)) { a.bytes.inputStream() }
            fun file(name: String, filename: String, mimeType: String, file: File) =
                AttachmentSource(name, filename, mimeType, file.length(), file.inputStream().use(::sha256)) { file.inputStream() }
        }
    }

    private fun submit(
        action: String, execution: AiExecution, taskType: String, instruction: String, schema: JsonNode?,
        attachments: List<AiAttachment>, artifacts: List<Map<String, Any>>, variant: String?,
        transcription: Map<String, String>?, synthesis: Map<String, Any>?
    ): String = submitSources(action, execution, taskType, instruction, schema, attachments.map(AttachmentSource::bytes), artifacts, variant, transcription, synthesis)

    private fun submitSources(
        action: String, execution: AiExecution, taskType: String, instruction: String, schema: JsonNode?,
        sources: List<AttachmentSource>, artifacts: List<Map<String, Any>>, variant: String?,
        transcription: Map<String, String>?, synthesis: Map<String, Any>?, role: String = "SOURCE"
    ): String {
        require(token.isNotBlank()) { "PNF_AGENT_RUNTIME_TOKEN ontbreekt" }
        val fingerprint = sha256(listOf(execution.toString(), taskType, schema?.toString().orEmpty(), instruction, variant.orEmpty(),
            mapper.writeValueAsString(transcription), mapper.writeValueAsString(synthesis), sources.joinToString { "${it.name}:${it.sha256}" }).joinToString(" ").toByteArray())
        val key = "pnf:$environment:$action:${fingerprint.take(40)}"
        if (sources.isNotEmpty()) findJob(key)?.let { return it }
        val objects = sources.map { source ->
            mapOf("objectId" to upload(source), "name" to source.name, "role" to if (source.name == "prompt") "PROMPT" else role)
        }
        val body = mapper.createObjectNode().apply {
            put("idempotencyKey", key)
            put("jobKind", "APPLICATION_WORK")
            put("taskType", taskType)
            putPOJO("execution", mapOf("vendorId" to execution.vendorId, "model" to execution.model, "mode" to execution.mode))
            putPOJO("input", mapOf("instruction" to instruction, "objects" to objects))
            val output = putObject("output")
            schema?.let { output.set("resultSchema", it) }
            output.putPOJO("artifacts", artifacts)
            put("executionTimeoutSeconds", actions.timeoutSeconds(action))
            transcription?.let { putPOJO("transcription", it) }
            synthesis?.let { putPOJO("synthesis", it) }
        }
        val response = send(HttpRequest.newBuilder(uri("/v2/jobs")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).timeout(Duration.ofSeconds(60)))
        if (response.statusCode() == 409) {
            return findJob(key) ?: throw IOException("Idempotency-conflict voor $key: ${response.body().take(300)}")
        }
        if (response.statusCode() != 202 && response.statusCode() != 200) throw IOException("Job aanmaken gaf HTTP ${response.statusCode()}: ${response.body().take(500)}")
        val job = mapper.readTree(response.body())
        log.info("[AgentRuntime] job {} voor {} ({}) status={}", job.path("id").asString(""), action, execution, job.path("status").asString(""))
        return job.path("id").asString()
    }

    private fun findJob(key: String): String? {
        var cursor: String? = null
        repeat(3) {
            val page = getJson("/v2/jobs?limit=100" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, StandardCharsets.UTF_8) } ?: ""))
            page.path("items").values().firstOrNull { it.path("idempotencyKey").asString("") == key }?.let { return it.path("id").asString() }
            cursor = page.path("nextCursor").asString(null) ?: return null
        }
        return null
    }

    private fun upload(source: AttachmentSource): String {
        val reservation = mapper.readTree(send(HttpRequest.newBuilder(uri("/v2/uploads")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("filename" to source.filename, "mimeType" to source.mimeType, "sizeBytes" to source.size, "sha256" to source.sha256))))
            .timeout(Duration.ofSeconds(60)), expect = 201).body())
        val uploadId = reservation.path("uploadId").asString()
        val chunk = reservation.path("chunkSizeBytes").asLong(8L * 1024 * 1024).coerceAtLeast(1024)
        var offset = reservation.path("offset").asLong(0)
        source.open().use { input ->
            input.skipNBytes(offset)
            while (offset < source.size) {
                val bytes = input.readNBytes(minOf(chunk, source.size - offset).toInt())
                if (bytes.isEmpty()) throw IOException("Bijlage ${source.name} is korter dan verwacht")
                val response = send(HttpRequest.newBuilder(uri("/v2/uploads/$uploadId")).header("Upload-Offset", offset.toString())
                    .header("Content-Type", "application/offset+octet-stream").method("PATCH", HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .timeout(Duration.ofMinutes(10)), expect = 204)
                offset = response.headers().firstValue("Upload-Offset").map(String::toLong).orElse(offset + bytes.size)
            }
        }
        val completed = mapper.readTree(send(HttpRequest.newBuilder(uri("/v2/uploads/$uploadId/complete")).POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofMinutes(2)), expect = 200).body())
        return completed.path("objectId").asString()
    }

    private fun await(jobId: String, cancelled: () -> Boolean): Outcome {
        val deadline = Instant.now().plus(Duration.ofMinutes(maxWaitMinutes))
        var cancelSent = false
        var failures = 0
        while (Instant.now().isBefore(deadline)) {
            val job = try { getJson("/v2/jobs/$jobId").also { failures = 0 } } catch (e: IOException) {
                if (++failures >= 30) throw e
                Thread.sleep(pollIntervalMs * 5); continue
            }
            when (val status = job.path("status").asString("")) {
                "SUCCEEDED", "FAILED", "CANCELLED" ->
                    return Outcome(status, listOfNotNull(job.path("errorCode").asString(null), job.path("errorMessage").asString(null)).joinToString(": ").ifBlank { status }, job.path("errorCode").asString(null))
            }
            if (!cancelSent && cancelled()) {
                runCatching { send(HttpRequest.newBuilder(uri("/v2/jobs/$jobId/cancel")).POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(30))) }
                cancelSent = true
            }
            Thread.sleep(pollIntervalMs)
        }
        return Outcome("TIMEOUT", "Job $jobId niet klaar binnen $maxWaitMinutes minuten (loopt door in de runtime)", "WAIT_TIMEOUT")
    }

    private fun logCall(action: String, username: String, started: Instant, subject: String?, execution: AiExecution, result: JsonNode?, status: String, error: String?, unitType: String = ExternalCall.UNIT_TOKENS) {
        val usage = result?.path("usageSummary")
        fun metric(name: String) = usage?.path("metrics")?.values()?.firstOrNull { it.path("metric").asString("") == name }?.path("quantity")?.asString("0")?.toBigDecimalOrNull()?.toLong()
        // Alleen werkelijke kosten tellen; API_EQUIVALENT is een schatting voor abonnementsjobs.
        val cost = usage?.path("costs")?.values()?.filter { it.path("currency").asString("") == "USD" && it.path("kind").asString("") in setOf("DIRECT", "CALCULATED") }
            ?.sumOf { it.path("amount").asString("0").toBigDecimalOrNull()?.toDouble() ?: 0.0 } ?: 0.0
        callLogger.logCall(
            provider = ExternalCall.PROVIDER_AGENT_RUNTIME, action = action, username = username, started = started,
            unitType = unitType, status = status, units = if (unitType == ExternalCall.UNIT_CHARACTERS) metric("CHARACTERS") else metric("OUTPUT_TOKENS"),
            costUsd = cost, errorMessage = error?.take(500), subject = listOfNotNull(subject, execution.toString()).joinToString(" · ").take(120),
            tokensIn = metric("INPUT_TOKENS"), tokensOut = metric("OUTPUT_TOKENS")
        )
    }

    private fun getJson(path: String): JsonNode =
        mapper.readTree(send(HttpRequest.newBuilder(uri(path)).GET().timeout(Duration.ofSeconds(60)), expect = 200).body())

    private fun download(path: String): ByteArray {
        val response = http.send(authorized(HttpRequest.newBuilder(uri(path)).GET().timeout(Duration.ofMinutes(10))), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) throw IOException("Download $path gaf HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun send(builder: HttpRequest.Builder, expect: Int? = null): HttpResponse<String> {
        val response = http.send(authorized(builder), HttpResponse.BodyHandlers.ofString())
        if (expect != null && response.statusCode() != expect) throw IOException("Agent Runtime gaf HTTP ${response.statusCode()}: ${response.body().take(500)}")
        return response
    }

    private fun authorized(builder: HttpRequest.Builder) = builder.header("Authorization", "Bearer $token").build()
    private fun uri(path: String) = URI.create(baseUrl.removeSuffix("/") + path)

    companion object {
        /** Ruim onder de runtime-limiet van 65.536 tekens; langere opdrachten gaan als bijlage. */
        const val MAX_INLINE_INSTRUCTION = 60_000
        private val FATAL_TRANSCRIPTION_CODES = setOf("INVALID_TRANSCRIPTION_INPUT", "OUTPUT_MIME_NOT_SUPPORTED", "OUTPUT_TOO_LARGE")

        private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
