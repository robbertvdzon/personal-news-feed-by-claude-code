package com.vdzon.newsfeedbackend.common

import org.springframework.security.core.context.SecurityContextHolder

object SecurityHelpers {
    fun currentUsername(): String =
        SecurityContextHolder.getContext().authentication?.name
            ?: throw UnauthorizedException("not authenticated")
}
