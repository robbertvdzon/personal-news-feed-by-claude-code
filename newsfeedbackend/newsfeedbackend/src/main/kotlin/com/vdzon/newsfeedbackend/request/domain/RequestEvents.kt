package com.vdzon.newsfeedbackend.request.domain

data class RequestCreatedEvent(val username: String, val requestId: String)
data class RequestRerunEvent(val username: String, val requestId: String)
