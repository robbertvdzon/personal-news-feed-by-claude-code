package com.vdzon.newsfeedbackend.websocket

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.auth.infrastructure.JwtService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import java.net.URI

/**
 * Unit-tests op de handshake-grens van `/ws/requests` (SF-2166): zonder geldig
 * token komt er geen sessie tot stand, mét geldig token staat de eigenaar in
 * `attributes["username"]` waarop [RequestWebSocketHandler.broadcast] filtert.
 */
class JwtHandshakeInterceptorTest {

    private val jwt = JwtService(secret = SECRET, ttlDays = 1)

    /**
     * De interceptor praat met de publieke module-API; die delegeert in
     * productie naar dezelfde [JwtService] (`AuthServiceImpl.validateToken`),
     * dus dat pad wordt hier gemockt met de échte validatie erachter.
     */
    private val auth = mock(AuthService::class.java).also {
        `when`(it.validateToken(anyString())).thenAnswer { inv -> jwt.validate(inv.getArgument(0)) }
    }
    private val interceptor = JwtHandshakeInterceptor(auth)
    private val handler = mock(WebSocketHandler::class.java)

    private fun handshake(query: String?): Pair<Boolean, MutableMap<String, Any>> {
        val request = mock(ServerHttpRequest::class.java)
        `when`(request.uri).thenReturn(
            URI.create("ws://localhost:8080/ws/requests" + (query?.let { "?$it" } ?: ""))
        )
        val response = mock(ServerHttpResponse::class.java)
        val attributes = mutableMapOf<String, Any>()
        val allowed = interceptor.beforeHandshake(request, response, handler, attributes)
        if (allowed) {
            verify(response, never()).setStatusCode(HttpStatus.UNAUTHORIZED)
        } else {
            verify(response).setStatusCode(HttpStatus.UNAUTHORIZED)
        }
        return allowed to attributes
    }

    @Test
    fun `een geldig token laat de handshake door en zet de gebruikersnaam`() {
        val token = jwt.create("alice", AuthService.ROLE_USER)

        val (allowed, attributes) = handshake("token=$token")

        assertTrue(allowed, "handshake met geldig token hoort te slagen")
        assertEquals("alice", attributes[JwtHandshakeInterceptor.ATTR_USERNAME])
    }

    @Test
    fun `zonder token-queryparameter wordt de handshake geweigerd met 401`() {
        val (allowed, attributes) = handshake(null)

        assertFalse(allowed, "handshake zonder token hoort geweigerd te worden")
        assertTrue(attributes.isEmpty(), "er hoort geen sessie-identiteit gezet te zijn")
    }

    @Test
    fun `een leeg token wordt geweigerd met 401`() {
        val (allowed, attributes) = handshake("token=")

        assertFalse(allowed)
        assertTrue(attributes.isEmpty())
    }

    @Test
    fun `een onzin-token wordt geweigerd met 401`() {
        val (allowed, attributes) = handshake("token=dit-is-geen-jwt")

        assertFalse(allowed)
        assertTrue(attributes.isEmpty())
    }

    @Test
    fun `een token dat met een ander secret is gesmeed wordt geweigerd`() {
        val vreemd = JwtService(secret = "een-heel-ander-secret-van-minstens-32-bytes", ttlDays = 1)
        val token = vreemd.create("mallory", AuthService.ROLE_ADMIN)

        val (allowed, attributes) = handshake("token=$token")

        assertFalse(allowed, "een token van een ander secret hoort ongeldig te zijn")
        assertTrue(attributes.isEmpty())
    }

    @Test
    fun `een verlopen token wordt geweigerd`() {
        val verlopen = JwtService(secret = SECRET, ttlDays = -1)
        val token = verlopen.create("alice", AuthService.ROLE_USER)

        val (allowed, _) = handshake("token=$token")

        assertFalse(allowed, "een verlopen token hoort geweigerd te worden")
    }

    companion object {
        private const val SECRET = "een-test-secret-van-minstens-32-bytes-lang"
    }
}
