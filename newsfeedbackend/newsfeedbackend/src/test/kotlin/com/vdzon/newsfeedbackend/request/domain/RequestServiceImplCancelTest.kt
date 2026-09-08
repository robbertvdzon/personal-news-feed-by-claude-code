package com.vdzon.newsfeedbackend.request.domain

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.request.infrastructure.RequestRepository
import com.vdzon.newsfeedbackend.websocket.RequestWebSocketHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.context.ApplicationEventPublisher

/**
 * Unit-tests op de eigenaarscheck in [RequestServiceImpl.cancel] (SF-2051):
 * annuleren raakt alleen het eigen verzoek en zet pas ná een geslaagde check
 * een — per gebruiker gekeyde — vlag in de cancellation-map.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestServiceImplCancelTest {

    private lateinit var repo: RequestRepository
    private lateinit var service: RequestServiceImpl

    /** Alle (username, request)-paren die naar de repository zijn geschreven. */
    private val upserts = mutableListOf<Pair<String, NewsRequest>>()

    /** Mockito.any() op een Kotlin non-null parameter geeft een NPE — zie agent-tips. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyObject(): T {
        Mockito.any<T>()
        return null as T
    }

    @BeforeEach
    fun setUp() {
        repo = mock(RequestRepository::class.java)
        service = RequestServiceImpl(
            repo,
            mock(AuthService::class.java),
            mock(RequestWebSocketHandler::class.java),
            mock(ApplicationEventPublisher::class.java)
        )
        upserts.clear()
        Mockito.doAnswer { inv ->
            val saved = inv.getArgument<NewsRequest>(1)
            upserts.add(inv.getArgument<String>(0) to saved)
            saved
        }.`when`(repo).upsert(anyString(), anyObject())
    }

    private fun request(id: String, status: RequestStatus = RequestStatus.PROCESSING) =
        NewsRequest(id = id, subject = "Onderwerp", status = status)

    private fun owns(username: String, vararg requests: NewsRequest) {
        `when`(repo.load(username)).thenReturn(requests.toMutableList())
    }

    @Test
    fun `annuleren van een eigen lopend verzoek zet CANCELLED en de cancel-vlag`() {
        owns("alice", request("req-1"))

        assertTrue(service.cancel("alice", "req-1"))
        assertTrue(service.isCancelled("alice", "req-1"))
        assertEquals(1, upserts.size)
        assertEquals("alice", upserts.single().first)
        assertEquals(RequestStatus.CANCELLED, upserts.single().second.status)
    }

    @Test
    fun `een andere gebruiker kan niet annuleren en laat geen sleutel achter`() {
        owns("alice", request("req-1"))
        owns("bob")

        assertFalse(service.cancel("bob", "req-1"))
        assertTrue(service.cancellation.isEmpty())
        // De vlag van alice is niet gezet: haar verwerking loopt gewoon door.
        assertFalse(service.isCancelled("alice", "req-1"))
        assertTrue(upserts.isEmpty())
    }

    @Test
    fun `onbekend id geeft false en laat geen sleutel achter`() {
        owns("alice")

        assertFalse(service.cancel("alice", "bestaat-niet"))
        assertTrue(service.cancellation.isEmpty())
        assertFalse(service.isCancelled("alice", "bestaat-niet"))
        assertTrue(upserts.isEmpty())
    }

    @Test
    fun `de cancel-vlag van de ene gebruiker raakt hetzelfde id bij de andere niet`() {
        owns("alice", request("gedeeld-id"))
        owns("bob", request("gedeeld-id"))

        assertTrue(service.cancel("alice", "gedeeld-id"))

        assertTrue(service.isCancelled("alice", "gedeeld-id"))
        assertFalse(service.isCancelled("bob", "gedeeld-id"))
    }

    @Test
    fun `een verzoek dat al afgerond is wordt niet overschreven maar geeft wel true`() {
        owns("alice", request("req-1", RequestStatus.DONE))

        assertTrue(service.cancel("alice", "req-1"))
        assertTrue(upserts.isEmpty())
    }

    @Test
    fun `rerun ruimt de cancel-vlag van dezelfde gebruiker op`() {
        owns("alice", request("req-1"))

        assertTrue(service.cancel("alice", "req-1"))
        assertTrue(service.isCancelled("alice", "req-1"))

        service.rerun("alice", "req-1")
        assertFalse(service.isCancelled("alice", "req-1"))
        assertTrue(service.cancellation.isEmpty())
    }
}
