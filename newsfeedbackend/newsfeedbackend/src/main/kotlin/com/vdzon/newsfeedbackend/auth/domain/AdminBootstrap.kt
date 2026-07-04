package com.vdzon.newsfeedbackend.auth.domain

import com.vdzon.newsfeedbackend.auth.infrastructure.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Bij upgrade van een installatie zonder rollen-veld in de users-opslag
 * bestaat er nog geen admin. Promote dan de oudste user (=eerste in de
 * lijst) tot admin, zodat het admin-scherm in de UI bereikbaar is.
 *
 * Stond eerst in common/StartupRunner; verhuisd naar de auth-module zodat
 * common geen businessmodules meer hoeft te kennen.
 */
@Component
class AdminBootstrap(private val users: UserRepository) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val all = users.all()
        if (all.isEmpty()) {
            log.info("Startup: no users — first registered user will become admin")
            return
        }
        if (all.any { it.role == User.ROLE_ADMIN }) return
        val first = all.first()
        users.update(first.copy(role = User.ROLE_ADMIN))
        log.warn("Startup: no admin found — promoted '{}' to admin", first.username)
    }
}
