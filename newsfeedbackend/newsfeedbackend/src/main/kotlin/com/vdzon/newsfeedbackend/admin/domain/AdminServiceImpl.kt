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

    /**
     * Ruimt `<dataDir>/users/<username>/audio` op.
     *
     * Dit is de vangnetlaag voor accounts van vóór de allowlist-validatie op de gebruikersnaam
     * in `AuthServiceImpl.register`: die accounts zijn niet met terugwerkende kracht gevalideerd,
     * dus een bestaande naam kan nog steeds `..` of een `/` bevatten. Daarom wordt hier
     * gecontroleerd dat het genormaliseerde doelpad aantoonbaar ónder `<dataDir>/users` valt;
     * valt het erbuiten, dan wordt er niets verwijderd en volgt alleen een `log.warn`.
     *
     * Bewust géén `toRealPath()`: `app.data-dir` heeft default `./data` en hoeft niet te
     * bestaan, wat een `NoSuchFileException` zou opleveren.
     *
     * Een lege naam levert `<dataDir>/users/audio` op — dat valt technisch ónder de basis en
     * wordt hier dus niet geweigerd. Die vorm wordt bewust door de registratie-validatie
     * afgevangen en niet hier.
     */
    private fun deleteAudioDir(username: String) {
        val base: Path = Path.of(dataDir, "users").toAbsolutePath().normalize()
        val dir: Path = Path.of(dataDir, "users", username, "audio").toAbsolutePath().normalize()
        if (!dir.startsWith(base)) {
            log.warn("[Admin] Refusing to delete audio dir outside {}: {}", base, dir)
            return
        }
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                runCatching { Files.deleteIfExists(p) }
                    .onFailure { log.warn("Could not delete {}: {}", p, it.message) }
            }
        }
    }
}
