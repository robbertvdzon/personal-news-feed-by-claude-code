package com.vdzon.newsfeedbackend.external_call

/**
 * Prijzen per externe partij/model in USD. Bewust een dom getal-table:
 * geen runtime-config, gewoon hardcoded en aanpasbaar in deze file.
 *
 * Bedragen zijn approximations — gebruik dit voor budget-bewustzijn,
 * niet voor accountancy.
 */
object Pricing {

    // Anthropic — prijzen per miljoen tokens
    fun anthropicCost(model: String, inputTokens: Long, outputTokens: Long): Double {
        val (inPrice, outPrice) = when {
            model.contains("haiku") -> 1.0 to 5.0
            model.contains("opus") -> 15.0 to 75.0
            else -> 3.0 to 15.0   // sonnet / default
        }
        return (inputTokens / 1_000_000.0) * inPrice + (outputTokens / 1_000_000.0) * outPrice
    }

    // OpenAI TTS — prijs per 1M characters voor model "tts-1"
    fun openaiTtsCost(characters: Long): Double = (characters / 1_000_000.0) * 15.0

    // ElevenLabs — prijs per character. Plan-afhankelijk; pak een ruwe gemiddelde.
    fun elevenlabsTtsCost(characters: Long): Double = (characters / 1000.0) * 0.30

    // Tavily — flat per call op het pay-as-you-go-tarief
    fun tavilySearchCost(): Double = 0.008      // ~$8 per 1k searches
    fun tavilyExtractCost(): Double = 0.005     // ~$5 per 1k extracts
}
