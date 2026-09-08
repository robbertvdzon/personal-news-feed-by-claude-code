package com.vdzon.newsfeedbackend.auth.infrastructure

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.vdzon.newsfeedbackend.common.UnauthorizedException
import java.net.URI
import java.time.Instant

data class GoogleIdentity(val email: String, val emailVerified: Boolean)

fun interface GoogleIdTokenVerifier {
    fun verify(idToken: String): GoogleIdentity
}

/** Verifieert Google-ID-tokens lokaal; alleen de publieke JWKS-sleutels worden bij Google opgehaald. */
class NimbusGoogleIdTokenVerifier(
    private val clientId: String,
    jwkSource: JWKSource<SecurityContext>,
) : GoogleIdTokenVerifier {

    constructor(clientId: String) : this(
        clientId,
        JWKSourceBuilder.create<SecurityContext>(URI.create(GOOGLE_JWKS_URL).toURL()).build(),
    )

    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
    }

    override fun verify(idToken: String): GoogleIdentity {
        val claims: JWTClaimsSet = try {
            processor.process(idToken, null)
        } catch (_: Exception) {
            throw unauthorized("Ongeldig Google ID-token")
        }
        if (clientId !in claims.audience.orEmpty()) throw unauthorized("Google ID-token met verkeerde audience")
        if (claims.issuer !in ACCEPTED_ISSUERS) throw unauthorized("Google ID-token met verkeerde issuer")
        val expiry = claims.expirationTime?.toInstant()
        if (expiry == null || expiry.isBefore(Instant.now())) throw unauthorized("Google ID-token is verlopen")
        val email = claims.getStringClaim("email").orEmpty().trim().lowercase()
        if (email.isBlank()) throw unauthorized("Google ID-token bevat geen e-mailadres")
        return GoogleIdentity(email, claims.getBooleanClaim("email_verified") ?: false)
    }

    private fun unauthorized(message: String) = UnauthorizedException(message)

    companion object {
        const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        val ACCEPTED_ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }
}
