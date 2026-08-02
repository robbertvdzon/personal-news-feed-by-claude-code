package com.vdzon.newsfeedbackend.external_call

/**
 * Log-interface die door de AI/TTS/Tavily clients wordt aangeroepen.
 *
 * Schrijft één regel naar `data/external_calls.jsonl` per call. Faalt
 * non-fataal: als loggen mislukt mag de business-flow gewoon doorlopen.
 */
interface ExternalCallLogger {
    fun log(call: ExternalCall)
}
