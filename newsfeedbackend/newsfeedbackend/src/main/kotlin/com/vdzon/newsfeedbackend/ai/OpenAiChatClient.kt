package com.vdzon.newsfeedbackend.ai

/**
 * KAN-63: aparte interface voor OpenAI chat-completions (gpt-4o-mini).
 * De bestaande [AnthropicClient] is Claude-specifiek (system+user-rol,
 * `x-api-key`-header), dus een nieuwe abstractie is goedkoper dan die
 * client uitbreiden met provider-switching. Het enige gebruik vandaag
 * is de NL-vertalingsstap in de podcast-translate-flow.
 */
interface OpenAiChatClient {
    fun complete(
        action: String,
        username: String,
        system: String,
        user: String,
        subject: String? = null,
        maxOutputTokens: Int = 16384
    ): OpenAiChatResponse

    fun translateModel(): String
}

data class OpenAiChatResponse(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val model: String,
    val status: String,
    val errorMessage: String? = null
)
