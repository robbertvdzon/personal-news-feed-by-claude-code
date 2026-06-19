package com.vdzon.newsfeedbackend.external_call

/**
 * Prijzen per externe partij/model in USD. Bewust een dom getal-table:
 * geen runtime-config, gewoon hardcoded en aanpasbaar in deze file.
 *
 * Bedragen zijn approximations — gebruik dit voor budget-bewustzijn,
 * niet voor accountancy.
 */
object Pricing {

    // OpenAI TTS — prijs per 1M characters voor model "tts-1"
    fun openaiTtsCost(characters: Long): Double = (characters / 1_000_000.0) * 15.0

    // OpenAI gpt-4o-mini — KAN-63: prijzen uit de story:
    //   $0.0005 / 1k input tokens, $0.002 / 1k output tokens.
    // Bewust de story-cijfers (niet de echte $0.15/$0.60 per 1M) zodat
    // de UI-cost-schatting en de gelogde call-kost overeenkomen met de
    // PO-keuze in KAN-63.
    fun openaiGpt4oMiniCost(inputTokens: Long, outputTokens: Long): Double =
        (inputTokens / 1000.0) * 0.0005 + (outputTokens / 1000.0) * 0.002

    // OpenAI chat — SF-114: generieke per-model tabel (per 1M tokens, in/out).
    // Bron: OpenAI pricing-pagina (GPT-5.4 officieel bevestigd; legacy via
    // aggregators). Tijdelijk hier; SF-117 verhuist deze tarieven naar config.
    fun openaiChatCost(model: String, inputTokens: Long, outputTokens: Long): Double {
        val (inPer1M, outPer1M) = when {
            model.contains("nano") -> 0.20 to 1.25
            model.contains("mini") -> 0.75 to 4.50
            model.startsWith("gpt-5") -> 2.50 to 15.0
            else -> 0.15 to 0.60   // gpt-4o-mini (echte tarieven)
        }
        return (inputTokens / 1_000_000.0) * inPer1M + (outputTokens / 1_000_000.0) * outPer1M
    }

    // ElevenLabs — prijs per character. Plan-afhankelijk; pak een ruwe gemiddelde.
    fun elevenlabsTtsCost(characters: Long): Double = (characters / 1000.0) * 0.30

    // Tavily — flat per call op het pay-as-you-go-tarief
    fun tavilySearchCost(): Double = 0.008      // ~$8 per 1k searches
    fun tavilyExtractCost(): Double = 0.005     // ~$5 per 1k extracts

    // OpenAI Whisper — prijs per minuut audio (afgerond naar boven). Pre-test
    // (KAN-56): 7 min NL-podcast → ~$0.042.
    fun openaiWhisperCost(seconds: Long): Double {
        val minutes = (seconds + 59) / 60.0
        return minutes * 0.006
    }
}
