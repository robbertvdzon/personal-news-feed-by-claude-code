package com.vdzon.newsfeedbackend.request.domain

import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.request.RequestCreatedEvent
import com.vdzon.newsfeedbackend.request.RequestRerunEvent
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.request.infrastructure.RequestRepository
import com.vdzon.newsfeedbackend.request.infrastructure.TavilyClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Component
class AdhocOrchestrator(
    private val service: RequestServiceImpl,
    private val repo: RequestRepository,
    private val tavily: TavilyClient,
    private val anthropic: AnthropicClient,
    private val feed: FeedService,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Async
    fun onCreated(event: RequestCreatedEvent) = process(event.username, event.requestId)

    @EventListener
    @Async
    fun onRerun(event: RequestRerunEvent) = process(event.username, event.requestId)

    fun process(username: String, requestId: String) {
        val current = repo.load(username).find { it.id == requestId } ?: return
        if (current.isDailyUpdate || current.isDailySummary) return // handled elsewhere

        MDC.put("username", username)
        MDC.put("requestId", requestId)
        try {
            log.info("[Request] start ad-hoc '{}' (id={})", current.subject, requestId)
            val started = Instant.now()
            service.upsert(username, current.copy(status = RequestStatus.PROCESSING, processingStartedAt = started))

            val results = tavily.search(current.subject, current.maxAgeDays, maxResults = current.maxCount * 3)
            val urls = results.take(current.maxCount).map { it.url }
            val texts = tavily.extract(urls)

            var totalCost = 0.0
            var newItems = 0

            for (r in results.take(current.maxCount)) {
                if (service.isCancelled(requestId)) {
                    service.upsert(username, current.copy(status = RequestStatus.CANCELLED, completedAt = Instant.now()))
                    log.info("[Request] cancelled id={}", requestId)
                    return
                }
                val text = texts[r.url] ?: r.snippet
                val ai = anthropic.complete(
                    operation = "summarizeArticle",
                    system = "Je bent een Nederlandstalige journalistieke samenvatter. Schrijf een heldere samenvatting van ~400 woorden in het Nederlands. Gebruik geen markdown headers maar wel paragrafen.",
                    user = "Onderwerp: ${current.subject}\n\nArtikel: ${r.title}\nURL: ${r.url}\n\nTekst:\n$text"
                )
                totalCost += ai.costUsd
                val feedItem = FeedItem(
                    id = UUID.randomUUID().toString(),
                    title = r.title,
                    summary = ai.text,
                    url = r.url,
                    source = extractDomain(r.url),
                    sourceUrls = listOf(r.url),
                    topics = listOf(current.subject),
                    feedReason = "Geselecteerd voor verzoek '${current.subject}'",
                    publishedDate = r.publishedDate?.take(10),
                    createdAt = Instant.now()
                )
                feed.save(username, feedItem)
                newItems++
                service.upsert(
                    username,
                    repo.load(username).find { it.id == requestId }!!.copy(
                        newItemCount = newItems,
                        costUsd = totalCost,
                        durationSeconds = ChronoUnit.SECONDS.between(started, Instant.now()).toInt()
                    )
                )
            }

            val finalReq = repo.load(username).find { it.id == requestId }!!.copy(
                status = RequestStatus.DONE,
                completedAt = Instant.now(),
                durationSeconds = ChronoUnit.SECONDS.between(started, Instant.now()).toInt()
            )
            service.upsert(username, finalReq)
            meters.counter("newsfeed.requests.processed", "type", "adhoc", "status", "DONE").increment()
            log.info("[Request] done id={} items={} cost={}", requestId, newItems, totalCost)
        } catch (e: Exception) {
            log.error("[Request] failed id=$requestId: ${e.message}", e)
            val current2 = repo.load(username).find { it.id == requestId } ?: return
            service.upsert(username, current2.copy(status = RequestStatus.FAILED, completedAt = Instant.now()))
            meters.counter("newsfeed.requests.processed", "type", "adhoc", "status", "FAILED").increment()
        } finally {
            MDC.clear()
        }
    }

    private fun extractDomain(url: String): String =
        Regex("https?://([^/]+)").find(url)?.groupValues?.get(1) ?: ""
}
