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
     * Callers kiezen zelf een fallback (meestal hun bestaande default), zodat
     * een ontbrekende config nooit een NPE oplevert.
     */
    fun modelFor(action: String): String? = models[action]
}
