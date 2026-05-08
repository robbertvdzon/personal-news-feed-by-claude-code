package com.vdzon.newsfeedbackend.auth.domain

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.auth.AuthToken
import com.vdzon.newsfeedbackend.auth.UserRegisteredEvent
import com.vdzon.newsfeedbackend.auth.infrastructure.JwtService
import com.vdzon.newsfeedbackend.auth.infrastructure.UserRepository
import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.common.ConflictException
import com.vdzon.newsfeedbackend.common.UnauthorizedException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AuthServiceImpl(
    private val users: UserRepository,
    private val jwt: JwtService,
    private val events: ApplicationEventPublisher
) : AuthService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val encoder = BCryptPasswordEncoder()

    override fun register(username: String, password: String): AuthToken {
        if (password.length < 4) throw BadRequestException("Password must be at least 4 characters")
        if (users.findByUsername(username) != null) throw ConflictException("Username already in use")
        val user = User(UUID.randomUUID().toString(), username, encoder.encode(password)!!)
        users.add(user)
        events.publishEvent(UserRegisteredEvent(username))
        log.info("Registered user '{}'", username)
        return AuthToken(jwt.create(username), username)
    }

    override fun login(username: String, password: String): AuthToken {
        val user = users.findByUsername(username) ?: throw UnauthorizedException("Invalid credentials")
        if (!encoder.matches(password, user.passwordHash)) throw UnauthorizedException("Invalid credentials")
        log.info("User '{}' logged in", username)
        return AuthToken(jwt.create(username), username)
    }

    override fun userExists(username: String): Boolean = users.findByUsername(username) != null

    override fun listUsernames(): List<String> = users.usernames()
}
