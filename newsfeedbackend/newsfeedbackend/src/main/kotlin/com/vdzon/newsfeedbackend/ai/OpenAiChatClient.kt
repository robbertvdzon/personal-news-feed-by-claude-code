package com.vdzon.newsfeedbackend.ai

/**
 * KAN-63: interface voor OpenAI chat-completions. Sinds SF-116 de enige
 * LLM-tekstclient: alle AI-tekstgeneratie draait op OpenAI. Naast de
 * oorspronkelijke NL-vertalingsstap (vast translate-model) biedt deze
 * client generieke [complete]/[completeJson] met expliciet model.
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
     * [AiModelProperties]).
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
