package com.vdzon.newsfeedbackend.external_call.infrastructure

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.springframework.stereotype.Component

@Component
class ExternalCallLoggerImpl(
    private val repo: ExternalCallRepository
) : ExternalCallLogger {

    override fun log(call: ExternalCall) {
        repo.append(call)
    }
}
