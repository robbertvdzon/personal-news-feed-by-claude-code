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
        // Eerste user die zich registreert wanneer er nog geen admin bestaat,
        // wordt automatisch admin. Daarna krijgen nieuwe registraties role=user.
        val role = if (!users.hasAdmin()) User.ROLE_ADMIN else User.ROLE_USER
        val user = User(UUID.randomUUID().toString(), username, encoder.encode(password)!!, role)
        users.add(user)
        events.publishEvent(UserRegisteredEvent(username))
        log.info("Registered user '{}' with role '{}'", username, role)
        return AuthToken(jwt.create(username, role), username, role)
    }

    override fun login(username: String, password: String): AuthToken {
        val user = users.findByUsername(username) ?: throw UnauthorizedException("Invalid credentials")
        if (!encoder.matches(password, user.passwordHash)) throw UnauthorizedException("Invalid credentials")
        log.info("User '{}' logged in (role={})", username, user.role)
        return AuthToken(jwt.create(username, user.role), username, user.role)
    }

    override fun userExists(username: String): Boolean = users.findByUsername(username) != null

    override fun listUsernames(): List<String> = users.usernames()

    override fun changePassword(username: String, currentPassword: String, newPassword: String) {
        if (newPassword.length < 4) throw BadRequestException("Password must be at least 4 characters")
        val user = users.findByUsername(username) ?: throw UnauthorizedException("Invalid credentials")
        if (!encoder.matches(currentPassword, user.passwordHash)) {
            throw UnauthorizedException("Huidig wachtwoord klopt niet")
        }
        users.update(user.copy(passwordHash = encoder.encode(newPassword)!!))
        log.info("User '{}' changed password", username)
    }
}
