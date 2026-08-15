package com.vdzon.newsfeedbackend.websocket

import com.vdzon.newsfeedbackend.auth.AuthService
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriComponentsBuilder

/**
 * Authenticeert de WebSocket-handshake van `/ws/requests`.
 *
 * Alle `/ws/`-paden staan in `SecurityConfig` bewust op `permitAll` — deze
 * interceptor is de grens. Het JWT komt als queryparameter `token` binnen (een browser-
 * WebSocket kan geen `Authorization`-header zetten); dat volgt het bestaande
 * precedent van het audio-endpoint in `JwtAuthFilter`. Het valideren zelf
 * gebeurt achter de publieke module-API [AuthService.validateToken], die op
 * dezelfde `JwtService` uitkomt als de servlet-keten.
 *
 * Ontbreekt het token of is het ongeldig/verlopen, dan wordt de handshake
 * geweigerd met status 401: er komt geen sessie tot stand en dus ook geen
 * `serverVersion`-bericht. Bij succes staat de gebruikersnaam in
 * `attributes["username"]`, waarop [RequestWebSocketHandler.broadcast] filtert.
 */
@Component
class JwtHandshakeInterceptor(private val auth: AuthService) : HandshakeInterceptor {

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>
    ): Boolean {
        val token = UriComponentsBuilder.fromUri(request.uri).build().queryParams
            .getFirst(TOKEN_PARAM)
            ?.takeIf { it.isNotBlank() }
        val parsed = token?.let { auth.validateToken(it) }
        if (parsed == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED)
            return false
        }
        attributes[ATTR_USERNAME] = parsed.first
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?
    ) {
        // Niets te doen: de sessie-identiteit is op beforeHandshake gezet.
    }

    companion object {
        /** Queryparameter waarin de client het JWT meestuurt. */
        const val TOKEN_PARAM = "token"

        /** Sleutel in `WebSocketSession.attributes` met de eigenaar van de sessie. */
        const val ATTR_USERNAME = "username"
    }
}
