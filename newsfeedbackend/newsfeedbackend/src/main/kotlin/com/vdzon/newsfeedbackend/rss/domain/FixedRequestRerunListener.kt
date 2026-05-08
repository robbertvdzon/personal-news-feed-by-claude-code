package com.vdzon.newsfeedbackend.rss.domain

import com.vdzon.newsfeedbackend.request.RequestRerunEvent
import com.vdzon.newsfeedbackend.rss.RssService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Routes rerun events for the two fixed request records to the
 * appropriate RSS-module pipelines:
 * - hourly-update-* → RssService.triggerRefresh (uurlijkse RSS-pipeline)
 * - daily-summary-* → RssScheduler.generateDailySummary (06:00-job)
 */
@Component
class FixedRequestRerunListener(
    private val rssService: RssService,
    private val rssScheduler: RssScheduler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Async
    fun onRerun(event: RequestRerunEvent) {
        when {
            event.requestId.startsWith("hourly-update-") -> {
                log.info("[Rerun] hourly-update -> trigger RSS refresh for '{}'", event.username)
                rssService.triggerRefresh(event.username)
            }
            event.requestId.startsWith("daily-summary-") -> {
                log.info("[Rerun] daily-summary -> regenerate for '{}'", event.username)
                rssScheduler.generateDailySummary(event.username)
            }
        }
    }
}
