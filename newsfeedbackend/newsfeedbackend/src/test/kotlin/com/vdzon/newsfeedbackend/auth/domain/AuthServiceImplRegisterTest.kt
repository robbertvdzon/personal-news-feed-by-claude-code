package com.vdzon.newsfeedbackend.auth.domain

import com.vdzon.newsfeedbackend.auth.infrastructure.JwtService
import com.vdzon.newsfeedbackend.auth.infrastructure.UserRepository
import com.vdzon.newsfeedbackend.common.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher

/**
 * Dekt de allowlist op de gebruikersnaam in [AuthServiceImpl.register].
 */
@ExtendWith(MockitoExtension::class)
class AuthServiceImplRegisterTest {

    private lateinit var users: UserRepository
    private lateinit var jwt: JwtService
    private lateinit var events: ApplicationEventPublisher
    private lateinit var service: AuthServiceImpl

    @BeforeEach
    fun setUp() {
        users = mock(UserRepository::class.java)
        jwt = mock(JwtService::class.java)
        events = mock(ApplicationEventPublisher::class.java)
        service = AuthServiceImpl(users, jwt, events)
    }

    @Test
    fun `accepts a plain username and returns a token`() {
        `when`(users.findByUsername("robbert")).thenReturn(null)
        `when`(users.hasAdmin()).thenReturn(true)
        `when`(jwt.create("robbert", User.ROLE_USER)).thenReturn("token-123")

        val result = service.register("robbert", "geheim")

        assertEquals("token-123", result.token)
        assertEquals("robbert", result.username)
        assertEquals(User.ROLE_USER, result.role)
        verify(users).add(userWith { it.username == "robbert" && it.role == User.ROLE_USER })
    }

    @Test
    fun `accepts a username with digits, dots, underscores and dashes`() {
        `when`(users.findByUsername("user-a1b2c3d4")).thenReturn(null)
        `when`(users.hasAdmin()).thenReturn(true)
        `when`(jwt.create("user-a1b2c3d4", User.ROLE_USER)).thenReturn("token-456")

        val result = service.register("user-a1b2c3d4", "geheim")

        assertEquals("token-456", result.token)
        verify(users).add(userWith { it.username == "user-a1b2c3d4" })
    }

    /**
     * Karakteriseringstest: legt bestaand gedrag vast, geen wens. De punt zit in de tekenset van
     * `USERNAME_PATTERN`, dus `..` wordt *niet* uitgesloten en `a..b` levert gewoon een token op.
     * Dat is geen gat — zonder `/` en `\` kan `..` nooit een eigen padsegment worden, en
     * `AdminServiceImpl.deleteAudioDir` heeft daaronder nog de containment-check op
     * `<data-dir>/users/`. Repareer deze assertie dus niet los: hij mag alleen wijzigen in
     * dezelfde diff die de regex zelf verscherpt (die wijziging kan bestaande accounts raken en
     * is stof voor een aparte story).
     */
    @Test
    fun `accepts a username containing consecutive dots`() {
        `when`(users.findByUsername("a..b")).thenReturn(null)
        `when`(users.hasAdmin()).thenReturn(true)
        `when`(jwt.create("a..b", User.ROLE_USER)).thenReturn("token-789")

        val result = service.register("a..b", "geheim")

        assertEquals("token-789", result.token)
        assertEquals("a..b", result.username)
        verify(users).add(userWith { it.username == "a..b" })
    }

    @Test
    fun `rejects an empty username`() = assertRejected("")

    @Test
    fun `rejects a username shorter than 3 characters`() = assertRejected("ab")

    @Test
    fun `rejects a username longer than 64 characters`() = assertRejected("a".repeat(65))

    @Test
    fun `rejects a username containing a slash`() = assertRejected("a/b")

    @Test
    fun `rejects a relative path style username because of its slash, not its dots`() =
        assertRejected("../x")

    @Test
    fun `rejects a username containing a newline`() = assertRejected("alice\nadmin")

    @Test
    fun `rejects an invalid username before the duplicate check so it is a 400 and not a 409`() {
        // Ook als de naam al zou bestaan, mag de duplicaat-check niet worden bereikt.
        val ex = assertThrows(BadRequestException::class.java) {
            service.register("../x", "geheim")
        }
        assertEquals(
            "Username must be 3-64 characters and may only contain letters, digits, '.', '_' and '-'",
            ex.message
        )
        verifyNoInteractions(users)
    }

    @Test
    fun `checks the password length before the username`() {
        assertThrows(BadRequestException::class.java) {
            service.register("a/b", "abc")
        }.also { assertEquals("Password must be at least 4 characters", it.message) }
    }

    /**
     * Mockito's `argThat` geeft `null` terug; Kotlin accepteert dat niet voor de non-null
     * parameter van [UserRepository.add]. Vandaar deze wrapper met een dummy-returnwaarde.
     */
    private fun userWith(predicate: (User) -> Boolean): User {
        argThat<User> { predicate(it) }
        return User("", "", "")
    }

    private fun assertRejected(username: String) {
        val ex = assertThrows(BadRequestException::class.java) {
            service.register(username, "geheim")
        }
        // De afgewezen naam komt bewust niet in de melding terug.
        assertEquals(
            "Username must be 3-64 characters and may only contain letters, digits, '.', '_' and '-'",
            ex.message
        )
        verifyNoInteractions(users)
        verifyNoInteractions(events)
    }
}
