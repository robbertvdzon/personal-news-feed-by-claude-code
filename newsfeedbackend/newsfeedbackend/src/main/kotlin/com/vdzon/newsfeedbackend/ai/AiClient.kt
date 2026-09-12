package com.vdzon.newsfeedbackend.ai

import tools.jackson.databind.JsonNode

/**
 * PNF-3: de enige toegang tot AI-werk. Alle LLM-, transcriptie- en TTS-
 * aanroepen lopen als job via de Agent Runtime (v2 API); de backend praat
 * zelf met geen enkele AI-provider meer. Welke vendor/model/mode een actie
 * gebruikt staat in [AiActionProperties].
 *
 * Contract: aanroepen blokkeren tot de job klaar is (of de wachttijd
 * verloopt) en gooien geen exceptions — fouten komen terug als
 * `status = "error"`. Aanroepers draaien daarom altijd op een
 * scheduler-/@Async-thread.
 */
interface AiClient {
    /** Structured generation: [AiRequest.resultSchema] bepaalt de vorm van [AiResponse.result]. */
    fun generate(request: AiRequest): AiResponse

    /** Speech-to-text van één audiobestand. */
    fun transcribe(request: TranscriptionRequest): TranscriptionResponse

    /** Text-to-speech; meerdere segmenten (stemmen) worden één MP3. */
    fun synthesize(request: SpeechRequest): SpeechResponse
}

/** Een bestand dat als invoerobject naar de job gaat (bv. artikelteksten of een transcript). */
data class AiAttachment(val name: String, val filename: String, val mimeType: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is AiAttachment && name == other.name && filename == other.filename &&
        mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * name.hashCode() + bytes.contentHashCode()

    companion object {
        fun text(name: String, content: String, mimeType: String = "text/markdown") =
            AiAttachment(name, "$name.${if (mimeType == "application/json") "json" else "md"}", mimeType, content.toByteArray())
    }
}

data class AiRequest(
    val action: String,
    val username: String,
    val subject: String?,
    val instruction: String,
    val resultSchema: JsonNode,
    val attachments: List<AiAttachment> = emptyList(),
    /**
     * Onderscheidt bewuste herhalingen met identieke invoer (bv. een rerun).
     * Zonder variant levert dezelfde invoer dezelfde (al betaalde) job op.
     */
    val variant: String? = null,
    /** Wordt tijdens het wachten gepold; `true` annuleert de runtime-job. */
    val cancelled: () -> Boolean = { false }
)

data class AiResponse(
    val result: JsonNode?,
    val status: String,
    val errorMessage: String? = null,
    val jobId: String? = null
) {
    val ok: Boolean get() = status == STATUS_OK && result != null

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_ERROR = "error"
        const val STATUS_CANCELLED = "cancelled"
    }
}

data class TranscriptionRequest(
    val username: String,
    val subject: String?,
    val audio: java.io.File,
    val mimeType: String = "audio/mpeg",
    val language: String? = null
)

sealed class TranscriptionResponse {
    data class Success(val text: String) : TranscriptionResponse()
    /** Tijdelijk probleem (runtime onbereikbaar, retrybare jobfout): later opnieuw proberen. */
    data class Retryable(val message: String) : TranscriptionResponse()
    /** Definitieve fout: niet zinvol om te herhalen. */
    data class Fatal(val message: String) : TranscriptionResponse()
}

data class SpeechSegment(val text: String, val voice: String, val speed: Double? = null)

data class SpeechRequest(
    val action: String,
    val username: String,
    val subject: String?,
    val segments: List<SpeechSegment>,
    val variant: String? = null
)

data class SpeechResponse(val audio: ByteArray?, val status: String, val errorMessage: String? = null) {
    val ok: Boolean get() = status == AiResponse.STATUS_OK && audio != null && audio.isNotEmpty()
    override fun equals(other: Any?) = other is SpeechResponse && status == other.status && errorMessage == other.errorMessage &&
        (audio?.contentEquals(other.audio) ?: (other.audio == null))
    override fun hashCode() = status.hashCode()
}
