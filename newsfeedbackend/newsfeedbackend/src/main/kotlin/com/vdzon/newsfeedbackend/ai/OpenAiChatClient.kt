package com.vdzon.newsfeedbackend.ai

/**
 * KAN-63: aparte interface voor OpenAI chat-completions (gpt-4o-mini).
 * De bestaande [AnthropicClient] is Claude-specifiek (system+user-rol,
 * `x-api-key`-header), dus een nieuwe abstractie is goedkoper dan die
 * client uitbreiden met provider-switching. Het enige gebruik vandaag
 * is de NL-vertalingsstap in de podcast-translate-flow.
 */
interface OpenAiChatClient {
    /**
     * KAN-63: vertaal-flow met het vaste translate-model. Behouden voor
     * bron-compat; nieuwe callers gebruiken de [complete] met `model`.
     */
    fun complete(
        action: String,
        username: String,
        system: String,
        user: String,
        subject: String? = null,
        maxOutputTokens: Int = 16384
    ): OpenAiChatResponse

    /**
     * SF-114: algemene chat-completion met een expliciet [model] (uit
     * [AiModelProperties]). Analoog aan [AnthropicClient.complete].
     */
    fun complete(
        model: String,
        action: String,
        username: String,
        system: String,
        user: String,
        subject: String? = null,
        maxOutputTokens: Int = 16384
    ): OpenAiChatResponse

    /**
     * SF-114: chat-completion die met OpenAI Structured Outputs een JSON-object
     * teruggeeft dat strikt aan [schema] voldoet (`strict:true`). Bedoeld voor
     * de JSON-extractietaken (event-/video-discovery, rss). [schema] is de
     * JSON-Schema als string; [schemaName] is de naam die OpenAI vereist.
     */
    fun completeJson(
        model: String,
        schemaName: String,
        schema: String,
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
