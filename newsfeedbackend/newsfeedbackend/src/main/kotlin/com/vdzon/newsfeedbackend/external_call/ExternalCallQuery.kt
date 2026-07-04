package com.vdzon.newsfeedbackend.external_call

import java.time.Instant

/**
 * Leestoegang tot het external-calls-logboek voor andere modules
 * (kosten-dashboards e.d.), zodat die niet aan de repository-internals
 * hoeven. Geïmplementeerd door de JDBC-repository in infrastructure.
 */
interface ExternalCallQuery {
    fun query(
        from: Instant? = null,
        to: Instant? = null,
        username: String? = null,
        provider: String? = null,
        action: String? = null,
        status: String? = null
    ): List<ExternalCall>

    fun all(): List<ExternalCall>
}
