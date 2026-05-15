package com.vdzon.newsfeedbackend.rss.domain

import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestService
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.rss.RssService
import com.vdzon.newsfeedbackend.rss.infrastructure.RssItemRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Component
class RssScheduler(
    private val auth: AuthService,
    private val rss: RssService,
    private val feed: FeedService,
    private val rssRepo: RssItemRepository,
    private val anthropic: AnthropicClient,
    private val requests: RequestService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "hourlyRefresh", lockAtMostFor = "59m", lockAtLeastFor = "1m")
    fun hourlyRefresh() {
        for (username in auth.listUsernames()) {
            log.info("[Scheduler] hourly refresh -> {}", username)
            rss.triggerRefresh(username)
        }
    }

    @Scheduled(cron = "0 0 6 * * *")
    @SchedulerLock(name = "dailySummary", lockAtMostFor = "4h", lockAtLeastFor = "1m")
    fun dailySummary() {
        for (username in auth.listUsernames()) {
            try {
                generateDailySummary(username)
            } catch (e: Exception) {
                log.error("[Summary] failed for {}: {}", username, e.message, e)
            }
        }
    }

    fun generateDailySummary(username: String) {
        val today = LocalDate.now()
        val id = "daily-summary-feed-$today"
        val now = Instant.now()
        val recentFeed = feed.list(username).filter { it.createdAt.isAfter(now.minus(1, ChronoUnit.DAYS)) }
        val recentRss = rssRepo.load(username).filter { it.timestamp.isAfter(now.minus(7, ChronoUnit.DAYS)) }
        val context = buildString {
            append("Feed-items van afgelopen 24 uur:\n")
            recentFeed.forEach { append("- ${it.title} (${it.category}): ${it.summary.take(200)}\n") }
            append("\nRSS-items van afgelopen 7 dagen:\n")
            recentRss.forEach { append("- ${it.title} (${it.category}): ${it.snippet.take(200)}\n") }
        }
        val ai = anthropic.complete(
            operation = "generateDailySummaryFromRss",
            action = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_DAILY_SUMMARY,
            username = username,
            subject = "Daily summary $today",
            system = "Je schrijft een dagelijkse Nederlandstalige nieuwsbriefing in Markdown (600-1000 woorden) met koppen, lijsten en duidingen.",
            user = context
        )
        feed.delete(username, id)
        feed.save(
            username, FeedItem(
                id = id,
                title = "Dagelijkse samenvatting $today",
                summary = ai.text,
                isSummary = true,
                createdAt = now,
                publishedDate = today.toString()
            )
        )
        val reqId = "daily-summary-$username"
        requests.upsert(
            username,
            requests.get(username, reqId)?.copy(
                status = RequestStatus.DONE,
                completedAt = now,
                newItemCount = 1
            ) ?: NewsRequest(
                id = reqId,
                subject = "Dagelijkse samenvatting",
                status = RequestStatus.DONE,
                completedAt = now,
                isDailySummary = true,
                newItemCount = 1
            )
        )
        log.info("[Summary] dagelijkse samenvatting aangemaakt voor '{}'", username)
    }
}
