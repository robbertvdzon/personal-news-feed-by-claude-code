package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.events.EventService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * KAN-66: wekelijkse AI-ontdekking van video's per event. Eigen cron, los
 * van de [EventScheduler] (events op 02:00) — draait zondag 03:00, één uur
 * later, met een eigen [SchedulerLock]. Triggert per gebruiker de
 * [EventVideoDiscoveryPipeline] (asynchroon via een Spring-event).
 *
 * lockAtMostFor=4h omdat een run met veel events × Tavily + Claude per event
 * traag kan zijn, net als de event-discovery.
 */
@Component
class EventVideoScheduler(
    private val auth: AuthService,
    private val events: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * SUN")
    @SchedulerLock(name = "weeklyEventVideoDiscovery", lockAtMostFor = "4h", lockAtLeastFor = "1m")
    fun weeklyDiscovery() {
        for (username in auth.listUsernames()) {
            log.info("[Scheduler] wekelijkse event-video-discovery -> {}", username)
            events.triggerVideoDiscovery(username)
        }
    }
}
