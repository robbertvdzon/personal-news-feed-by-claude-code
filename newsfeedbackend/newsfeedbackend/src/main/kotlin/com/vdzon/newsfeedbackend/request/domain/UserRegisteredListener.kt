package com.vdzon.newsfeedbackend.request.domain

import com.vdzon.newsfeedbackend.auth.UserRegisteredEvent
import com.vdzon.newsfeedbackend.request.RequestService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class UserRegisteredListener(private val requests: RequestService) {

    @EventListener
    fun onRegistered(event: UserRegisteredEvent) {
        requests.ensureFixedRequests(event.username)
    }
}
