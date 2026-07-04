package com.vdzon.newsfeedbackend.auth.infrastructure

import com.vdzon.newsfeedbackend.auth.domain.User
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtService(
    @param:Value("\${app.jwt.secret:}") private val secret: String,
    @param:Value("\${app.jwt.ttl-days:30}") private val ttlDays: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Zonder geconfigureerd secret (APP_JWT_SECRET, in prod via
     * SealedSecret) genereren we een random ephemeral secret: de app
     * werkt dan gewoon (lokaal ontwikkelen, tests), maar alle tokens
     * zijn ongeldig na een herstart. Er is bewust GEEN hardcoded
     * default meer — daarmee kon een misconfiguratie in prod stilletjes
     * draaien op een publiek bekend secret waarmee iedereen tokens kan
     * smeden.
     */
    private val key: SecretKey by lazy {
        val bytes = if (secret.isBlank()) {
            log.warn(
                "Geen app.jwt.secret geconfigureerd — ephemeral secret gegenereerd; " +
                    "alle JWT's worden ongeldig bij herstart. Zet APP_JWT_SECRET voor productie."
            )
            ByteArray(64).also { SecureRandom().nextBytes(it) }
        } else {
            secret.toByteArray(StandardCharsets.UTF_8)
        }
        require(bytes.size >= 32) { "JWT secret must be at least 32 bytes" }
        Keys.hmacShaKeyFor(bytes)
    }

    fun create(username: String, role: String): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject(username)
            .claim(CLAIM_ROLE, role)
            .issuedAt(Date(now))
            .expiration(Date(now + ttlDays * 24 * 3600 * 1000))
            .signWith(key)
            .compact()
    }

    /** Returns (username, role) of een geldige token, of null. */
    fun validate(token: String): Pair<String, String>? = try {
        val claims: Claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
        val sub = claims.subject ?: return null
        val role = claims[CLAIM_ROLE] as? String ?: User.ROLE_USER
        sub to role
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val CLAIM_ROLE = "role"
    }
}
