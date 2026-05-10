package com.vdzon.newsfeedbackend.ai

/**
 * Public interface for the AI module.
 * All AI prompts go through this client.
 *
 * Elke call wordt gelogd in `data/external_calls.jsonl` met de
 * meegegeven `action` en `username`. `operation` is alleen voor de
 * Micrometer-metrics-naam en mag een ontwikkelaar-friendly tag zijn.
 */
interface AnthropicClient {

    fun complete(
        operation: String,
        action: String,
        username: String,
        system: String,
        user: String,
        model: String? = null,
        maxTokens: Int = 4096,
        subject: String? = null
    ): AiResponse

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
