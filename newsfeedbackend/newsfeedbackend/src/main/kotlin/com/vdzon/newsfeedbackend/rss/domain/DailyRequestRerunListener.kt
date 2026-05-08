package com.vdzon.newsfeedbackend.rss.domain

import com.vdzon.newsfeedbackend.request.RequestRerunEvent
import com.vdzon.newsfeedbackend.rss.RssService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class DailyRequestRerunListener(
    private val rssService: RssService,
    private val rssScheduler: RssScheduler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Async
    fun onRerun(event: RequestRerunEvent) {
        when {
            event.requestId.startsWith("daily-update-") -> {
                log.info("[Rerun] daily-update -> trigger RSS refresh for '{}'", event.username)
                rssService.triggerRefresh(event.username)
            }
            event.requestId.startsWith("daily-summary-") -> {
                log.info("[Rerun] daily-summary -> regenerate for '{}'", event.username)
                rssScheduler.generateDailySummary(event.username)
            }
        }
    }
}
