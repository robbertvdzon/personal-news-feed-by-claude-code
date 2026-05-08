package com.vdzon.newsfeedbackend.common

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.request.RequestService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class StartupRunner(
    private val auth: AuthService,
    private val requests: RequestService
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
