package com.vdzon.newsfeedbackend.auth.infrastructure

import com.vdzon.newsfeedbackend.auth.domain.User
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtService(
    @Value("\${app.jwt.secret}") private val secret: String,
    @Value("\${app.jwt.ttl-days:30}") private val ttlDays: Long
) {

    private val key: SecretKey by lazy {
        val bytes = secret.toByteArray(StandardCharsets.UTF_8)
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
