package com.vdzon.newsfeedbackend.auth.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GoogleAuthConfig {
    @Bean
    fun googleIdTokenVerifier(config: GoogleLoginConfig): GoogleIdTokenVerifier =
        NimbusGoogleIdTokenVerifier(config.clientId)
}
