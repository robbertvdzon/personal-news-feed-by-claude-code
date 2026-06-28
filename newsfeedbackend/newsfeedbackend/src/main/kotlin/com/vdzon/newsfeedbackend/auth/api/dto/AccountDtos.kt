package com.vdzon.newsfeedbackend.auth.api.dto

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)
