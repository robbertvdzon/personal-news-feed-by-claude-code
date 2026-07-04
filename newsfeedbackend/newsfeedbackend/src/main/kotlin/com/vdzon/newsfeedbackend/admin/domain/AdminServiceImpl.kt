package com.vdzon.newsfeedbackend.admin.domain

import com.vdzon.newsfeedbackend.admin.AdminService
import com.vdzon.newsfeedbackend.admin.AdminUserView
import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.common.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Admin-guardrails (mag deze actor dit?) leven hier; de eigenlijke
 * account-mutaties lopen via de publieke [AuthService]-API zodat admin
 * niet in de internals van de auth-module hoeft te grijpen.
 */
@Service
class AdminServiceImpl(
    private val auth: AuthService,
    @param:Value("\${app.data-dir:./data}") private val dataDir: String
) : AdminService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun listUsers(): List<AdminUserView> = auth.listAccounts().map {
        AdminUserView(it.id, it.username, it.role)
    }

    override fun resetPassword(targetUsername: String, newPassword: String, actor: String) {
        auth.resetPassword(targetUsername, newPassword)
        log.info("[Admin] '{}' reset password for '{}'", actor, targetUsername)
    }

    override fun setRole(targetUsername: String, newRole: String, actor: String) {
        val target = auth.findAccount(targetUsername) ?: throw NotFoundException("User not found: $targetUsername")
        if (target.username == actor && target.role == AuthService.ROLE_ADMIN && newRole != AuthService.ROLE_ADMIN) {
            // Voorkom dat een admin per ongeluk zichzelf demote en de laatste
            // admin-toegang kwijtraakt.
            throw BadRequestException("Je kunt je eigen admin-rol niet verwijderen")
        }
        auth.setRole(targetUsername, newRole)
        log.info("[Admin] '{}' changed role of '{}' to '{}'", actor, targetUsername, newRole)
    }

    override fun deleteUser(targetUsername: String, actor: String) {
        if (targetUsername == actor) throw BadRequestException("Je kunt jezelf niet verwijderen")
        // Postgres FK ON DELETE CASCADE ruimt alle per-user tabellen op.
        if (!auth.deleteUser(targetUsername)) {
            throw NotFoundException("User not found: $targetUsername")
        }
        // Audio-files staan nog op disk; opruimen.
        deleteAudioDir(targetUsername)
        log.info("[Admin] '{}' deleted user '{}'", actor, targetUsername)
    }

    private fun deleteAudioDir(username: String) {
        val dir: Path = Path.of(dataDir, "users", username, "audio")
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                runCatching { Files.deleteIfExists(p) }
                    .onFailure { log.warn("Could not delete {}: {}", p, it.message) }
            }
        }
    }
}
