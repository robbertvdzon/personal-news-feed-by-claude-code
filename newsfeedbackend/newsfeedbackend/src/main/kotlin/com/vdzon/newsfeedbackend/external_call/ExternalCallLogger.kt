package com.vdzon.newsfeedbackend.external_call

import java.time.Instant

/**
 * Log-interface die door de AI/TTS/Tavily clients wordt aangeroepen.
 *
 * Schrijft één regel naar `data/external_calls.jsonl` per call. Faalt
 * non-fataal: als loggen mislukt mag de business-flow gewoon doorlopen.
 */
interface ExternalCallLogger {
    fun log(call: ExternalCall)

    /**
     * Convenience: timer rondom een externe call. Vangt exceptions niet —
     * alleen handig om start/end consistent te zetten.
     */
    fun timed(
        provider: String,
        action: String,
        username: String,
        unitType: String,
        subject: String? = null,
        block: TimedContext.() -> Unit
    )

    /**
     * Mutable context die je in `block` invult met tokens/units/cost/error.
     * Logger gebruikt deze om de definitieve [ExternalCall] te bouwen.
     */
    class TimedContext {
        var tokensIn: Long? = null
        var tokensOut: Long? = null
        var units: Long? = null
        var costUsd: Double = 0.0
        var status: String = "ok"
        var errorMessage: String? = null
        val started: Instant = Instant.now()
    }
}
