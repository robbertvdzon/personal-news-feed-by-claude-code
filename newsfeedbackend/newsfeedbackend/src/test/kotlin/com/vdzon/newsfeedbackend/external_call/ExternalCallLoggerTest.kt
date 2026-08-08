package com.vdzon.newsfeedbackend.external_call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * SF-2022: de default-implementatie [ExternalCallLogger.logCall] vult zelf
 * id/endTime/durationMs in en slikt fouten in. Dat gedrag is nu gedeeld door
 * alle acht aanroepers, dus het verdient een eigen test.
 */
class ExternalCallLoggerTest {

    private class RecordingLogger : ExternalCallLogger {
        val calls = mutableListOf<ExternalCall>()
        override fun log(call: ExternalCall) {
            calls.add(call)
        }
    }

    @Test
    fun `vult id, endTime en durationMs zelf in en geeft de rest ongewijzigd door`() {
        val logger = RecordingLogger()
        val started = Instant.now().minusMillis(50)

        logger.logCall(
            ExternalCall.PROVIDER_OPENAI, ExternalCall.ACTION_RSS_SUMMARIZE, "alice", started,
            ExternalCall.UNIT_TOKENS, "ok",
            units = 30, costUsd = 0.25, errorMessage = null, subject = "een onderwerp",
            tokensIn = 10, tokensOut = 20
        )

        assertEquals(1, logger.calls.size)
        val call = logger.calls.single()
        assertNotNull(call.id)
        assertEquals(ExternalCall.PROVIDER_OPENAI, call.provider)
        assertEquals(ExternalCall.ACTION_RSS_SUMMARIZE, call.action)
        assertEquals("alice", call.username)
        assertEquals(started, call.startTime)
        assertTrue(!call.endTime.isBefore(started), "endTime mag niet vóór startTime liggen")
        assertEquals(call.endTime.toEpochMilli() - started.toEpochMilli(), call.durationMs)
        assertEquals(ExternalCall.UNIT_TOKENS, call.unitType)
        assertEquals("ok", call.status)
        assertEquals(30L, call.units)
        assertEquals(0.25, call.costUsd)
        assertNull(call.errorMessage)
        assertEquals("een onderwerp", call.subject)
        assertEquals(10L, call.tokensIn)
        assertEquals(20L, call.tokensOut)
    }

    @Test
    fun `defaults laten units, cost, error, subject en tokens leeg`() {
        val logger = RecordingLogger()

        logger.logCall(
            ExternalCall.PROVIDER_RSS, ExternalCall.ACTION_RSS_FETCH, "system", Instant.now(),
            ExternalCall.UNIT_ITEMS, "error"
        )

        val call = logger.calls.single()
        assertNull(call.units)
        assertEquals(0.0, call.costUsd)
        assertNull(call.errorMessage)
        assertNull(call.subject)
        assertNull(call.tokensIn)
        assertNull(call.tokensOut)
    }

    @Test
    fun `kapt subject bewust niet af - dat blijft bij de aanroeper`() {
        val logger = RecordingLogger()
        val longSubject = "x".repeat(200)

        logger.logCall(
            ExternalCall.PROVIDER_WEB, ExternalCall.ACTION_ARTICLE_FETCH, "bob", Instant.now(),
            ExternalCall.UNIT_CHARACTERS, "ok", subject = longSubject
        )

        assertEquals(longSubject, logger.calls.single().subject)
    }

    @Test
    fun `slikt een falende log in zodat de business-flow doorloopt`() {
        val exploding = object : ExternalCallLogger {
            override fun log(call: ExternalCall) = throw IllegalStateException("db down")
        }

        // Mag geen exception naar de aanroeper laten ontsnappen.
        exploding.logCall(
            ExternalCall.PROVIDER_TAVILY, ExternalCall.ACTION_TAVILY_SEARCH, "carol", Instant.now(),
            ExternalCall.UNIT_QUERIES, "ok", units = 1
        )
    }
}
