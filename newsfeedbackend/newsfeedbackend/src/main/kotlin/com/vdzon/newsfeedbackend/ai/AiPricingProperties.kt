package com.vdzon.newsfeedbackend.ai

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * SF-117: tarieven per AI-model in config (niet langer hardcoded in
 * `Pricing.kt`). Per model wordt de afrekeneenheid en het tarief vastgelegd,
 * zodat de per-call `cost_usd` (gelogd in `external_calls`) uit deze config
 * komt en een tarief bijwerken geen code-wijziging vraagt.
 *
 * Drie afrekeneenheden worden ondersteund:
 *  - `tokens`     → [ModelPricing.inputPerMillion] / [ModelPricing.outputPerMillion]
 *                   (USD per 1M input/output tokens) — chat-modellen.
 *  - `seconds`    → [ModelPricing.perMinute] (USD per minuut audio, naar boven
 *                   afgerond) — transcriptie.
 *  - `characters` → [ModelPricing.perMillionChars] (USD per 1M characters) —
 *                   TTS.
 *
 * Config staat in `application.properties` onder `app.ai.pricing.*`. Model-keys
 * met punten (bv. `gpt-5.4-mini`) gebruiken bracket-notatie:
 * `app.ai.pricing.models[gpt-5.4-mini].input-per-million=0.75`.
 *
 * [source] en [updated] documenteren de bron-URL en datum-laatste-update,
 * zodat handmatig bijwerken makkelijk blijft.
 */
@ConfigurationProperties("app.ai.pricing")
class AiPricingProperties {
    /** Bron-URL van de tarieven (OpenAI pricing-pagina). */
    var source: String = ""
    /** Datum waarop de tarieven voor het laatst zijn bijgewerkt (ISO-datum). */
    var updated: String = ""
    /** model-id → tarief. Gevoed vanuit `app.ai.pricing.models[<model>].*`. */
    var models: MutableMap<String, ModelPricing> = mutableMapOf()

    class ModelPricing {
        /** USD per 1M input-tokens (afrekeneenheid `tokens`). */
        var inputPerMillion: Double = 0.0
        /** USD per 1M output-tokens (afrekeneenheid `tokens`). */
        var outputPerMillion: Double = 0.0
        /** USD per minuut audio (afrekeneenheid `seconds`). */
        var perMinute: Double = 0.0
        /** USD per 1M characters (afrekeneenheid `characters`). */
        var perMillionChars: Double = 0.0
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** Token-kosten (chat) voor [model] uit config; 0 als het model ontbreekt. */
    fun tokenCost(model: String, inputTokens: Long, outputTokens: Long): Double {
        val p = lookup(model) ?: return 0.0
        return (inputTokens / 1_000_000.0) * p.inputPerMillion +
            (outputTokens / 1_000_000.0) * p.outputPerMillion
    }

    /** Transcriptie-kosten voor [model]: per minuut (naar boven afgerond). */
    fun transcriptionCost(model: String, seconds: Long): Double {
        val p = lookup(model) ?: return 0.0
        val minutes = (seconds + 59) / 60
        return minutes * p.perMinute
    }

    /** TTS-kosten voor [model] per 1M characters. */
    fun characterCost(model: String, characters: Long): Double {
        val p = lookup(model) ?: return 0.0
        return (characters / 1_000_000.0) * p.perMillionChars
    }

    private fun lookup(model: String): ModelPricing? {
        val p = models[model]
        if (p == null) {
            log.warn("[Pricing] geen tarief geconfigureerd voor model '{}' — cost_usd=0.0", model)
        }
        return p
    }
}
