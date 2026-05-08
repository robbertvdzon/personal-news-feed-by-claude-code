package com.vdzon.newsfeedbackend.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.CopyOnWriteArrayList

@Component
class RequestWebSocketHandler(private val mapper: ObjectMapper) : TextWebSocketHandler() {

    private val sessions = CopyOnWriteArrayList<WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions.add(session)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessions.remove(session)
    }

    fun broadcast(payload: Any) {
        val msg = TextMessage(mapper.writeValueAsString(payload))
        val dead = mutableListOf<WebSocketSession>()
        sessions.forEach { s ->
            try {
                if (s.isOpen) s.sendMessage(msg) else dead.add(s)
            } catch (e: Exception) {
                dead.add(s)
            }
        }
        sessions.removeAll(dead)
    }
}

@Configuration
@EnableWebSocket
class WebSocketConfig(private val handler: RequestWebSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/requests").setAllowedOrigins("*")
    }
}
