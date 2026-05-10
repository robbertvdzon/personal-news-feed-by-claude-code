package com.vdzon.newsfeedbackend.common

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.auth.domain.User
import com.vdzon.newsfeedbackend.auth.infrastructure.UserRepository
import com.vdzon.newsfeedbackend.request.RequestService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class StartupRunner(
    private val auth: AuthService,
    private val users: UserRepository,
    private val requests: RequestService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        log.info("Startup: resetting stuck requests")
        requests.resetStuck()
        ensureAdminExists()
        for (username in auth.listUsernames()) {
            requests.ensureFixedRequests(username)
        }
    }

    /**
     * Bij upgrade van een installatie zonder rollen-veld in users.json bestaat
     * er nog geen admin. Promote dan de oudste user (=eerste in de lijst) tot
     * admin, zodat het admin-scherm in de UI bereikbaar is.
     */
    private fun ensureAdminExists() {
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
