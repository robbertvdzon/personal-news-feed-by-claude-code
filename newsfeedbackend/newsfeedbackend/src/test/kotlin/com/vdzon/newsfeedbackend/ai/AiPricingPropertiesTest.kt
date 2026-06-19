package com.vdzon.newsfeedbackend.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SF-117: per-model cost-berekening uit config voor de drie afrekeneenheden
 * (tokens, seconden/minuten, characters).
 */
class AiPricingPropertiesTest {

    private fun props(): AiPricingProperties = AiPricingProperties().apply {
        models["gpt-5.4-mini"] = AiPricingProperties.ModelPricing().apply {
            inputPerMillion = 0.75
            outputPerMillion = 4.50
        }
        models["gpt-4o-mini-transcribe"] = AiPricingProperties.ModelPricing().apply {
            perMinute = 0.003
        }
        models["tts-1"] = AiPricingProperties.ModelPricing().apply {
            perMillionChars = 15.0
        }
    }

    @Test
    fun `token cost uses input and output rates per million`() {
        // 1M input * 0.75 + 1M output * 4.50 = 5.25
        assertEquals(5.25, props().tokenCost("gpt-5.4-mini", 1_000_000, 1_000_000), 1e-9)
    }

    @Test
    fun `transcription cost rounds seconds up to whole minutes`() {
        // 61s -> ceil naar 2 min * 0.003 = 0.006
        assertEquals(0.006, props().transcriptionCost("gpt-4o-mini-transcribe", 61), 1e-9)
    }

    @Test
    fun `character cost uses per million characters rate`() {
        // 500k chars * (15.0 / 1M) = 7.5
        assertEquals(7.5, props().characterCost("tts-1", 500_000), 1e-9)
    }

    @Test
    fun `unknown model yields zero cost instead of throwing`() {
        assertEquals(0.0, props().tokenCost("does-not-exist", 1_000_000, 1_000_000), 1e-9)
        assertEquals(0.0, props().transcriptionCost("does-not-exist", 600), 1e-9)
        assertEquals(0.0, props().characterCost("does-not-exist", 1_000_000), 1e-9)
    }
}
