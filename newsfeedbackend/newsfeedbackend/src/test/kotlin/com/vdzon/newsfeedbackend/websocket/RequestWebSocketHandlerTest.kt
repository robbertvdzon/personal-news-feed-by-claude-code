package com.vdzon.newsfeedbackend.websocket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper

/**
 * Unit-tests op de per-eigenaar-levering van [RequestWebSocketHandler]
 * (SF-2166): een statusbericht gaat alleen naar de sessies van de eigenaar,
 * en dode sessies worden nog steeds opgeruimd.
 */
class RequestWebSocketHandlerTest {

    private val mapper = ObjectMapper()
    private val handler = RequestWebSocketHandler(mapper)

    /** Verzamelt de payloads die naar één sessie zijn gestuurd. */
    private class Sent {
        val messages = mutableListOf<String>()
    }

    private fun session(username: String?, open: Boolean = true, failOnSend: Boolean = false): Pair<WebSocketSession, Sent> {
        val session = mock(WebSocketSession::class.java)
        val sent = Sent()
        val attributes = mutableMapOf<String, Any>()
        if (username != null) attributes[JwtHandshakeInterceptor.ATTR_USERNAME] = username
        `when`(session.attributes).thenReturn(attributes)
        `when`(session.isOpen).thenReturn(open)
        // sendMessage is void: stubben kan alleen via doAnswer(...).when(mock).
        Mockito.doAnswer { inv ->
            if (failOnSend) throw IllegalStateException("sessie kapot")
            sent.messages.add((inv.getArgument<WebSocketMessage<*>>(0) as TextMessage).payload)
            null
        }.`when`(session).sendMessage(ArgumentMatchers.any(WebSocketMessage::class.java))
        return session to sent
    }

    /** Registreert de sessie en gooit het `serverVersion`-bericht weg. */
    private fun connect(session: WebSocketSession, sent: Sent) {
        handler.afterConnectionEstablished(session)
        sent.messages.clear()
    }

    @Test
    fun `een statusbericht gaat alleen naar de sessies van de eigenaar`() {
        val (aliceA, sentAliceA) = session("alice")
        val (aliceB, sentAliceB) = session("alice")
        val (bob, sentBob) = session("bob")
        connect(aliceA, sentAliceA)
        connect(aliceB, sentAliceB)
        connect(bob, sentBob)

        handler.broadcast("alice", mapOf("id" to "r1", "status" to "DONE"))

        assertEquals(1, sentAliceA.messages.size)
        assertEquals(1, sentAliceB.messages.size)
        assertTrue(sentAliceA.messages.single().contains("\"id\":\"r1\""))
        assertTrue(sentBob.messages.isEmpty(), "bob hoort niets van alice te ontvangen: ${sentBob.messages}")
    }

    @Test
    fun `het serverVersion-bericht gaat alleen naar de verbindende sessie`() {
        val (alice, sentAlice) = session("alice")
        handler.afterConnectionEstablished(alice)

        val (bob, sentBob) = session("bob")
        handler.afterConnectionEstablished(bob)

        assertEquals(1, sentAlice.messages.size, "alice kreeg een tweede bericht: ${sentAlice.messages}")
        assertTrue(sentAlice.messages.single().contains("\"type\":\"serverVersion\""))
        assertEquals(1, sentBob.messages.size)
    }

    @Test
    fun `een sessie zonder gebruikersnaam ontvangt niets`() {
        // Kan in de praktijk niet ontstaan (de interceptor weigert zo'n
        // handshake); de filter mag er hoe dan ook niet op terugvallen.
        val (anoniem, sentAnoniem) = session(null)
        connect(anoniem, sentAnoniem)

        handler.broadcast("alice", mapOf("id" to "r1"))

        assertTrue(sentAnoniem.messages.isEmpty())
    }

    @Test
    fun `een gesloten sessie blokkeert de levering aan de andere sessie niet en wordt opgeruimd`() {
        val (dicht, sentDicht) = session("alice", open = false)
        val (open, sentOpen) = session("alice")
        connect(open, sentOpen)
        handler.afterConnectionEstablished(dicht)
        sentDicht.messages.clear()

        handler.broadcast("alice", mapOf("id" to "r1"))

        assertEquals(1, sentOpen.messages.size)
        assertTrue(sentDicht.messages.isEmpty())

        // Opgeruimd: een tweede broadcast raakt hem niet meer, en het
        // sluiten van een al verwijderde sessie blijft veilig.
        handler.broadcast("alice", mapOf("id" to "r2"))
        assertEquals(2, sentOpen.messages.size)
        handler.afterConnectionClosed(dicht, CloseStatus.NORMAL)
    }

    @Test
    fun `een sessie die bij het sturen faalt wordt opgeruimd zonder de rest te raken`() {
        val (kapot, sentKapot) = session("alice", failOnSend = true)
        val (gezond, sentGezond) = session("alice")
        handler.afterConnectionEstablished(kapot)
        connect(gezond, sentGezond)
        sentKapot.messages.clear()

        handler.broadcast("alice", mapOf("id" to "r1"))
        handler.broadcast("alice", mapOf("id" to "r2"))

        assertEquals(2, sentGezond.messages.size)
        assertTrue(sentKapot.messages.isEmpty())
    }

    @Test
    fun `na afterConnectionClosed ontvangt de sessie niets meer`() {
        val (alice, sentAlice) = session("alice")
        connect(alice, sentAlice)

        handler.afterConnectionClosed(alice, CloseStatus.NORMAL)
        handler.broadcast("alice", mapOf("id" to "r1"))

        assertTrue(sentAlice.messages.isEmpty())
    }
}
