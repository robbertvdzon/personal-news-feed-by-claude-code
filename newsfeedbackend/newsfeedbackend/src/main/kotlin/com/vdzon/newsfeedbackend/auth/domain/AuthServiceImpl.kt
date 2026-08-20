package com.vdzon.newsfeedbackend.auth.domain

import com.vdzon.newsfeedbackend.auth.AuthenticatedUser
import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.auth.AuthToken
import com.vdzon.newsfeedbackend.auth.UserAccount
import com.vdzon.newsfeedbackend.auth.UserRegisteredEvent
import com.vdzon.newsfeedbackend.auth.infrastructure.JwtService
import com.vdzon.newsfeedbackend.auth.infrastructure.UserRepository
import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.common.ConflictException
import com.vdzon.newsfeedbackend.common.NotFoundException
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

    /**
     * Registreert een nieuwe gebruiker.
     *
     * De gebruikersnaam moet aan [USERNAME_PATTERN] voldoen. Die allowlist staat er om twee
     * redenen: pad-veiligheid (de naam wordt gebruikt als padsegment onder `app.data-dir`, dus
     * `/` en `\` mogen er nooit in zitten) en logveiligheid (een regeleinde in de naam
     * maakt logvervalsing mogelijk). De validatie staat bewust ná de wachtwoordcheck en vóór de
     * duplicaat-check, zodat een ongeldige naam altijd een `400` geeft en nooit een `409`.
     *
     * [login] en [changePassword] valideren de naam bewust niet: accounts die van vóór deze
     * regel dateren moeten kunnen blijven inloggen.
     */
    override fun register(username: String, password: String): AuthToken {
        if (password.length < 4) throw BadRequestException("Password must be at least 4 characters")
        if (!USERNAME_PATTERN.matches(username)) {
            throw BadRequestException(
                "Username must be 3-64 characters and may only contain letters, digits, '.', '_' and '-'"
            )
        }
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

    override fun deleteOwnAccount(username: String): Boolean {
        val deleted = users.deleteByUsername(username)
        if (deleted) {
            log.info("User '{}' deleted own account", username)
        }
        return deleted
    }

    override fun validateToken(token: String): AuthenticatedUser? = jwt.validate(token)

    override fun listAccounts(): List<UserAccount> =
        users.all().map { UserAccount(it.id, it.username, it.role) }

    override fun findAccount(username: String): UserAccount? =
        users.findByUsername(username)?.let { UserAccount(it.id, it.username, it.role) }

    override fun resetPassword(username: String, newPassword: String) {
        if (newPassword.length < 4) throw BadRequestException("Password must be at least 4 characters")
        val user = users.findByUsername(username) ?: throw NotFoundException("User not found: $username")
        users.update(user.copy(passwordHash = encoder.encode(newPassword)!!))
    }

    override fun setRole(username: String, role: String) {
        if (role != User.ROLE_USER && role != User.ROLE_ADMIN) {
            throw BadRequestException("Invalid role: $role (allowed: ${User.ROLE_USER}, ${User.ROLE_ADMIN})")
        }
        val user = users.findByUsername(username) ?: throw NotFoundException("User not found: $username")
        users.update(user.copy(role = role))
    }

    override fun deleteUser(username: String): Boolean = users.deleteByUsername(username)

    companion object {
        /**
         * Allowlist voor nieuwe gebruikersnamen: 3-64 tekens uit `[A-Za-z0-9._-]`.
         * Sluit in één regel de lege naam, `/`, `\`, spaties, null-bytes en
         * regeleindes uit. `..` is bewust *niet* uitgesloten (de punt zit in de tekenset, dus
         * `a..b` wordt geaccepteerd), maar kan door het ontbreken van `/` en `\` nooit een
         * eigen padsegment worden; de containment-check op `<data-dir>/users/` in
         * `AdminServiceImpl.deleteAudioDir` is de laag daaronder.
         * Houd deze grenzen gelijk aan `AuthRequest.username` in
         * `specs/openapi.yaml` en aan §3 van `specs/backend-functional-spec.md`.
         */
        private val USERNAME_PATTERN = Regex("^[A-Za-z0-9._-]{3,64}$")
    }
}
