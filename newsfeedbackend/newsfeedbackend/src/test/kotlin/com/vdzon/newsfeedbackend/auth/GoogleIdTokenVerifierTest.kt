package com.vdzon.newsfeedbackend.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.vdzon.newsfeedbackend.auth.infrastructure.GoogleLoginConfig
import com.vdzon.newsfeedbackend.auth.infrastructure.NimbusGoogleIdTokenVerifier
import com.vdzon.newsfeedbackend.common.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Date

class GoogleIdTokenVerifierTest {
    private val clientId = "test-client-id.apps.googleusercontent.com"
    private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val jwks = ImmutableJWKSet<SecurityContext>(JWKSet(signingKey.toPublicJWK()))
    private val verifier = NimbusGoogleIdTokenVerifier(clientId, jwks)

    @Test
    fun `verifieert signature audience issuer expiry en e-mail`() {
        val identity = verifier.verify(token(email = "Robbert@Example.com"))

        assertEquals("robbert@example.com", identity.email)
        assertEquals(true, identity.emailVerified)
    }

    @Test
    fun `weigert verkeerde audience issuer expiry en signature`() {
        assertThrows(UnauthorizedException::class.java) { verifier.verify(token(audience = "other")) }
        assertThrows(UnauthorizedException::class.java) { verifier.verify(token(issuer = "https://evil.example")) }
        assertThrows(UnauthorizedException::class.java) {
            verifier.verify(token(expiresAt = Date(System.currentTimeMillis() - 1_000)))
        }
        assertThrows(UnauthorizedException::class.java) { verifier.verify("not-a-jwt") }
    }

    @Test
    fun `parseert uitbreidbare e-mail naar username mapping`() {
        val users = GoogleLoginConfig.parseUsers(
            "RobbertVdzon@gmail.com=robbert, iemand@example.com=iemand"
        )

        assertEquals("robbert", users.getValue("robbertvdzon@gmail.com").username)
        assertEquals("iemand", users.getValue("iemand@example.com").username)
    }

    private fun token(
        email: String = "robbert@example.com",
        audience: String = clientId,
        issuer: String = "https://accounts.google.com",
        expiresAt: Date = Date(System.currentTimeMillis() + 3_600_000),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("123456789")
            .expirationTime(expiresAt)
            .issueTime(Date())
            .claim("email", email)
            .claim("email_verified", true)
            .build()
        return SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(),
            claims,
        ).also { it.sign(RSASSASigner(signingKey)) }.serialize()
    }
}
