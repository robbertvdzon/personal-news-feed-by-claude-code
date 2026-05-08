package com.vdzon.newsfeedbackend.ai

/**
 * Public interface for the AI module.
 * All AI prompts go through this client.
 */
interface AnthropicClient {

    fun complete(operation: String, system: String, user: String, model: String? = null, maxTokens: Int = 4096): AiResponse

    fun summaryModel(): String
    fun mainModel(): String
}

data class AiResponse(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val model: String
)
