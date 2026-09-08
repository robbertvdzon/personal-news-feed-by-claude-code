package com.vdzon.newsfeedbackend.external_call

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val EXTERNAL_CALL_LOG = LoggerFactory.getLogger(ExternalCallLogger::class.java)

/**
 * Log-interface die door de AI/TTS/Tavily/RSS-clients wordt aangeroepen.
 *
 * Schrijft één rij naar de Postgres-tabel `external_calls` per call.
 *
 * Het non-fatale gedrag zit sinds SF-2022 in de interface zelf: wie via
 * [logCall] logt krijgt de exception-afhandeling gratis mee, zodat de
 * business-flow gewoon doorloopt als loggen mislukt. [log] blijft het
 * enige contractlid dat een implementatie moet invullen.
 */
interface ExternalCallLogger {
    fun log(call: ExternalCall)

    /**
     * Bouwt en logt één [ExternalCall]. Vult zelf `id` (random UUID),
     * `endTime` (nu) en `durationMs` (verschil met [started]) in en
     * delegeert naar [log]. Elke exception wordt hier ingeslikt met één
     * waarschuwing — loggen mag de aanroeper nooit stukmaken.
     *
     * [subject] gaat kant-en-klaar naar binnen: er wordt hier bewust niet
     * afgekapt, dat blijft de verantwoordelijkheid van de aanroeper.
     */
    fun logCall(
        provider: String,
        action: String,
        username: String,
        started: Instant,
        unitType: String,
        status: String,
        units: Long? = null,
        costUsd: Double = 0.0,
        errorMessage: String? = null,
        subject: String? = null,
        tokensIn: Long? = null,
        tokensOut: Long? = null
    ) {
        val end = Instant.now()
        try {
            log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = provider,
                    action = action,
                    username = username,
                    startTime = started,
                    endTime = end,
                    durationMs = end.toEpochMilli() - started.toEpochMilli(),
                    tokensIn = tokensIn,
                    tokensOut = tokensOut,
                    units = units,
                    unitType = unitType,
                    costUsd = costUsd,
                    status = status,
                    errorMessage = errorMessage,
                    subject = subject
                )
            )
        } catch (e: Exception) {
            EXTERNAL_CALL_LOG.warn("[ExternalCallLog] could not log external_call: {}", e.message)
        }
    }
}
