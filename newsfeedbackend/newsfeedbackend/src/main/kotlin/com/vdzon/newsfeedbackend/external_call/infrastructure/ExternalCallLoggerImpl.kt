package com.vdzon.newsfeedbackend.external_call.infrastructure

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class ExternalCallLoggerImpl(
    private val repo: ExternalCallRepository
) : ExternalCallLogger {

    override fun log(call: ExternalCall) {
        repo.append(call)
    }

    override fun timed(
        provider: String,
        action: String,
        username: String,
        unitType: String,
        subject: String?,
        block: ExternalCallLogger.TimedContext.() -> Unit
    ) {
        val ctx = ExternalCallLogger.TimedContext()
        try {
            block(ctx)
        } catch (e: Exception) {
            ctx.status = "error"
            ctx.errorMessage = e.message ?: e.javaClass.simpleName
            // re-throw zodat de business-flow gewoon zijn eigen error-handling kan doen
            recordAndAppend(provider, action, username, unitType, subject, ctx)
            throw e
        }
        recordAndAppend(provider, action, username, unitType, subject, ctx)
    }

    private fun recordAndAppend(
        provider: String,
        action: String,
        username: String,
        unitType: String,
        subject: String?,
        ctx: ExternalCallLogger.TimedContext
    ) {
        val end = Instant.now()
        repo.append(
            ExternalCall(
                id = UUID.randomUUID().toString(),
                provider = provider,
                action = action,
                username = username,
                startTime = ctx.started,
                endTime = end,
                durationMs = end.toEpochMilli() - ctx.started.toEpochMilli(),
                tokensIn = ctx.tokensIn,
                tokensOut = ctx.tokensOut,
                units = ctx.units,
                unitType = unitType,
                costUsd = ctx.costUsd,
                status = ctx.status,
                errorMessage = ctx.errorMessage,
                subject = subject
            )
        )
    }
}
