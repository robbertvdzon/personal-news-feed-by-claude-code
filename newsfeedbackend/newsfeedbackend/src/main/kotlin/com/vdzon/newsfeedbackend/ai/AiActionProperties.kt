package com.vdzon.newsfeedbackend.ai

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * PNF-3: koppeling actie → Agent Runtime-uitvoering, als `vendor/model/MODE`
 * (bv. `anthropic/claude-sonnet-5/SUBSCRIPTION`). Keys zijn de actie-
 * constanten uit [com.vdzon.newsfeedbackend.external_call.ExternalCall].
 * Defaults staan in `application.properties` onder `app.ai.actions.*` en
 * zijn per env-var (`PNF_AI_*`) overschrijfbaar.
 */
@ConfigurationProperties("app.ai")
class AiActionProperties {
    /** action-constante → `vendor/model/MODE`. */
    var actions: MutableMap<String, String> = mutableMapOf()

    /** action-constante → hard deadline per runtime-attempt in seconden. */
    var timeouts: MutableMap<String, Int> = mutableMapOf()

    fun execution(action: String): AiExecution {
        val configured = actions[action]
        if (configured == null) {
            log.warn("Geen AI-uitvoering geconfigureerd voor actie '{}' — fallback naar '{}'", action, DEFAULT)
        }
        return AiExecution.parse(configured ?: DEFAULT)
    }

    fun timeoutSeconds(action: String): Int = timeouts[action] ?: DEFAULT_TIMEOUT_SECONDS

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(AiActionProperties::class.java)
        const val DEFAULT = "anthropic/claude-sonnet-5/SUBSCRIPTION"
        const val DEFAULT_TIMEOUT_SECONDS = 1800
    }
}

data class AiExecution(val vendorId: String, val model: String, val mode: String) {
    override fun toString() = "$vendorId/$model/$mode"

    companion object {
        fun parse(value: String): AiExecution {
            val parts = value.trim().split('/')
            require(parts.size == 3 && parts.all { it.isNotBlank() }) { "Ongeldige AI-uitvoering '$value' (verwacht vendor/model/MODE)" }
            return AiExecution(parts[0].trim(), parts[1].trim(), parts[2].trim().uppercase())
        }
    }
}
