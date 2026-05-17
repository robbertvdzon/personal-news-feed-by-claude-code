package com.vdzon.newsfeedbackend.podcastfeed.domain

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.podcastfeed.PodcastFeedService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Triggert elk uur de podcast-pipeline voor alle users. ShedLock zodat
 * een tweede pod (canary/blue-green) geen dubbele Whisper-kosten maakt.
 */
@Component
class PodcastFeedScheduler(
    private val auth: AuthService,
    private val podcastFeeds: PodcastFeedService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 5 * * * *")
    @SchedulerLock(name = "hourlyPodcastRefresh", lockAtMostFor = "59m", lockAtLeastFor = "1m")
    fun hourlyRefresh() {
        for (username in auth.listUsernames()) {
            log.info("[Scheduler] hourly podcast refresh -> {}", username)
            podcastFeeds.triggerRefresh(username)
        }
    }
}
