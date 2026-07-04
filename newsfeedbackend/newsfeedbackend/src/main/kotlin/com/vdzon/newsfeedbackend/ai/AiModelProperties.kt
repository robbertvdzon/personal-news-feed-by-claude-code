package com.vdzon.newsfeedbackend.ai

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * SF-114: koppeling actie → AI-model in config.
 *
 * De keys in [models] zijn de actie-constanten uit
 * [com.vdzon.newsfeedbackend.external_call.ExternalCall] (bv. `rss_summarize`,
 * `daily_summary`, `podcast_transcribe`). Defaults staan in
 * `application.properties` onder `app.ai.models.*` en zijn per env-var
 * (`PNF_AI_MODEL_*`) overschrijfbaar, zodat een model wisselen geen
 * code-wijziging vraagt.
 *
 * Deze story levert alleen het fundament: callers blijven (voorlopig) hun
 * bestaande model gebruiken; de daadwerkelijke wissels gebeuren in SF-115.
 */
@ConfigurationProperties("app.ai")
class AiModelProperties {
    /** action-constante → model-id. Gevoed vanuit `app.ai.models.<actie>`. */
    var models: MutableMap<String, String> = mutableMapOf()

    /**
     * Het geconfigureerde model voor [action], of null als er geen mapping is.
     * Voor callers met een eigen, niet-model-gebonden fallback (zoals de
     * Whisper-client); alle andere callers gebruiken [modelOrDefault].
     */
    fun modelFor(action: String): String? = models[action]

    /**
     * Het geconfigureerde model voor [action], met centrale fallback als de
     * mapping ontbreekt. Vervangt de losse `modelFor(x) ?: "gpt-…"`-
     * constructies die elk hun eigen kopie van de default hadden — de
     * fallback-tabel staat nu op één plek, en een stille terugval wordt
     * gelogd zodat een config-fout niet onopgemerkt blijft.
     */
    fun modelOrDefault(action: String): String {
        models[action]?.let { return it }
        val fallback = FALLBACKS[action] ?: DEFAULT_MODEL
        log.warn("Geen AI-model geconfigureerd voor actie '{}' — fallback naar '{}'", action, fallback)
        return fallback
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(AiModelProperties::class.java)

        const val DEFAULT_MODEL = "gpt-5.4-mini"

        /**
         * Per-actie fallback voor acties die bewust een ander model dan de
         * default gebruiken. Spiegel van de defaults in
         * `application.properties` (app.ai.models.*).
         */
        private val FALLBACKS = mapOf(
            "daily_summary" to "gpt-5.4",
            "event_video_summarize" to "gpt-5.4",
            "event_discovery_date" to "gpt-5.4-nano"
        )
    }
}
