package com.vdzon.newsfeedbackend.auth.infrastructure

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

    fun create(username: String): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject(username)
            .issuedAt(Date(now))
            .expiration(Date(now + ttlDays * 24 * 3600 * 1000))
            .signWith(key)
            .compact()
    }

    fun validate(token: String): String? = try {
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
            .subject
    } catch (e: Exception) {
        null
    }
}
