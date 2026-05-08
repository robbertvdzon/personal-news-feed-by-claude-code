package com.vdzon.newsfeedbackend.auth.domain

data class User(
    val id: String,
    val username: String,
    val passwordHash: String
)
