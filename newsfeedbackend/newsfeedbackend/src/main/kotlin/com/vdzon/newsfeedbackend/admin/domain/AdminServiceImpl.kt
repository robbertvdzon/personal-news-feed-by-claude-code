package com.vdzon.newsfeedbackend.admin.domain

import com.vdzon.newsfeedbackend.admin.AdminService
import com.vdzon.newsfeedbackend.admin.AdminUserView
import com.vdzon.newsfeedbackend.auth.domain.User
import com.vdzon.newsfeedbackend.auth.infrastructure.UserRepository
import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.common.NotFoundException
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Service
class AdminServiceImpl(
    private val users: UserRepository,
    private val store: JsonStore
) : AdminService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val encoder = BCryptPasswordEncoder()

    override fun listUsers(): List<AdminUserView> = users.all().map {
        AdminUserView(it.id, it.username, it.role)
    }

    override fun resetPassword(targetUsername: String, newPassword: String, actor: String) {
        if (newPassword.length < 4) throw BadRequestException("Password must be at least 4 characters")
        val target = users.findByUsername(targetUsername) ?: throw NotFoundException("User not found: $targetUsername")
        val updated = target.copy(passwordHash = encoder.encode(newPassword)!!)
        users.update(updated)
        log.info("[Admin] '{}' reset password for '{}'", actor, targetUsername)
    }

    override fun setRole(targetUsername: String, newRole: String, actor: String) {
        if (newRole != User.ROLE_USER && newRole != User.ROLE_ADMIN) {
            throw BadRequestException("Invalid role: $newRole (allowed: ${User.ROLE_USER}, ${User.ROLE_ADMIN})")
        }
        val target = users.findByUsername(targetUsername) ?: throw NotFoundException("User not found: $targetUsername")
        if (target.username == actor && target.role == User.ROLE_ADMIN && newRole != User.ROLE_ADMIN) {
            // Voorkom dat een admin per ongeluk zichzelf demote en de laatste
            // admin-toegang kwijtraakt.
            throw BadRequestException("Je kunt je eigen admin-rol niet verwijderen")
        }
        users.update(target.copy(role = newRole))
        log.info("[Admin] '{}' changed role of '{}' to '{}'", actor, targetUsername, newRole)
    }

    override fun deleteUser(targetUsername: String, actor: String) {
        if (targetUsername == actor) throw BadRequestException("Je kunt jezelf niet verwijderen")
        val target = users.findByUsername(targetUsername) ?: throw NotFoundException("User not found: $targetUsername")
        if (!users.deleteByUsername(targetUsername)) {
            throw NotFoundException("User not found: $targetUsername")
        }
        // Verwijder ook de data-map zodat de gebruiker volledig weg is.
        deleteUserDataDir(target.username)
        log.info("[Admin] '{}' deleted user '{}'", actor, targetUsername)
    }

    private fun deleteUserDataDir(username: String) {
        val dir: Path = store.root().resolve("users").resolve(username)
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                runCatching { Files.deleteIfExists(p) }
                    .onFailure { log.warn("Could not delete {}: {}", p, it.message) }
            }
        }
    }
}
