package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.events.EventService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * KAN-65: wekelijkse AI-ontdekking van tech-events. Eigen cron, los van
 * de RssScheduler. Draait zondag 02:00 en triggert per gebruiker de
 * [EventDiscoveryPipeline] (asynchroon via een Spring-event).
 *
 * lockAtMostFor=4h omdat een run met veel gebruikers × categorieën traag
 * kan zijn (Tavily + Claude per categorie).
 */
@Component
class EventScheduler(
    private val auth: AuthService,
    private val events: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 * * SUN")
    @SchedulerLock(name = "weeklyEventDiscovery", lockAtMostFor = "4h", lockAtLeastFor = "1m")
    fun weeklyDiscovery() {
        for (username in auth.listUsernames()) {
            log.info("[Scheduler] wekelijkse event-discovery -> {}", username)
            events.triggerDiscovery(username)
        }
    }
}
