package com.vdzon.newsfeedbackend.external_call

/**
 * Prijzen voor externe partijen waarvan de tarieven (nog) niet per model in
 * config staan. Bewust een dom getal-table: aanpasbaar in deze file.
 *
 * SF-117: de OpenAI-tarieven (chat-tokens, transcriptie per minuut, TTS per
 * character) zijn verhuisd naar config — zie
 * [com.vdzon.newsfeedbackend.ai.AiPricingProperties] (`app.ai.pricing.*`).
 * Hier blijven alleen de niet-OpenAI-providers (ElevenLabs, Tavily) staan.
 *
 * Bedragen zijn approximations — gebruik dit voor budget-bewustzijn,
 * niet voor accountancy.
 */
object Pricing {

    // ElevenLabs — prijs per character. Plan-afhankelijk; pak een ruwe gemiddelde.
    fun elevenlabsTtsCost(characters: Long): Double = (characters / 1000.0) * 0.30

    // Tavily — flat per call op het pay-as-you-go-tarief
    fun tavilySearchCost(): Double = 0.008      // ~$8 per 1k searches
    fun tavilyExtractCost(): Double = 0.005     // ~$5 per 1k extracts
}
