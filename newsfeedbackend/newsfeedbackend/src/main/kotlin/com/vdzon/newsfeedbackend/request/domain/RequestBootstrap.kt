package com.vdzon.newsfeedbackend.request.domain

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.request.RequestService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Herstelt bij het opstarten requests die door een crash/redeploy in
 * PROCESSING zijn blijven hangen en zorgt dat elke bestaande user zijn
 * vaste (hourly/daily) requests heeft.
 *
 * Stond eerst in common/StartupRunner; verhuisd naar de request-module
 * zodat common geen businessmodules meer hoeft te kennen.
 */
@Component
class RequestBootstrap(
    private val requests: RequestService,
    private val auth: AuthService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        log.info("Startup: resetting stuck requests")
        requests.resetStuck()
        for (username in auth.listUsernames()) {
            requests.ensureFixedRequests(username)
        }
    }
}
