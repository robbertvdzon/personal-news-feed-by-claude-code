package com.vdzon.newsfeedbackend.request.domain

import com.vdzon.newsfeedbackend.ai.AiClient
import com.vdzon.newsfeedbackend.ai.AiRequest
import com.vdzon.newsfeedbackend.ai.AiResponse
import tools.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.request.RequestCreatedEvent
import com.vdzon.newsfeedbackend.request.RequestRerunEvent
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.request.infrastructure.RequestRepository
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
    private val ai: AiClient,
    private val mapper: ObjectMapper,
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
        if (current.isHourlyUpdate || current.isDailySummary) return // handled elsewhere

        MDC.put("username", username)
        MDC.put("requestId", requestId)
        try {
            log.info("[Request] start ad-hoc '{}' (id={})", current.subject, requestId)
            val started = Instant.now()
            service.upsert(username, current.copy(status = RequestStatus.PROCESSING, processingStartedAt = started))

            // PNF-3: één agent-job die zelf zoekt (web search), de artikelen leest
            // en samenvat — geen aparte zoek-API meer.
            val response = ai.generate(
                AiRequest(
                    action = ExternalCall.ACTION_ADHOC_SUMMARIZE,
                    username = username,
                    subject = "Adhoc: ${current.subject.take(80)}",
                    resultSchema = mapper.readTree(SCHEMA),
                    variant = "$requestId-$started",
                    cancelled = { service.isCancelled(username, requestId) },
                    instruction = """
                        Je bent een Nederlandstalige journalistieke onderzoeker.
                        Zoek met je web search tool naar actuele nieuwsartikelen over het onderwerp: "${current.subject}".
                        Neem alleen artikelen die in de afgelopen ${current.maxAgeDays} dagen zijn gepubliceerd (vandaag is ${java.time.LocalDate.now()}).
                        Lees elk gekozen artikel echt (web fetch) en gebruik uitsluitend URL's die je daadwerkelijk hebt gelezen; verzin nooit een URL of datum.
                        Kies maximaal ${current.maxCount} verschillende, relevante artikelen van betrouwbare bronnen; geen dubbele verhalen.
                        Schrijf per artikel een heldere samenvatting van ~400 woorden in het Nederlands, zonder markdown headers maar met paragrafen.
                        publishedDate in formaat YYYY-MM-DD, of null als onbekend.
                    """.trimIndent()
                )
            )
            if (response.status == AiResponse.STATUS_CANCELLED || service.isCancelled(username, requestId)) {
                repo.load(username).find { it.id == requestId }?.let {
                    service.upsert(username, it.copy(status = RequestStatus.CANCELLED, completedAt = Instant.now()))
                }
                log.info("[Request] cancelled id={}", requestId)
                return
            }
            if (!response.ok) throw IllegalStateException("AI-zoekopdracht mislukt: ${response.errorMessage}")

            var newItems = 0
            val seen = mutableSetOf<String>()
            for (r in response.result!!.path("items").values().take(current.maxCount)) {
                val url = r.path("url").asString("").trim()
                if (!url.startsWith("http") || !seen.add(url)) continue
                val publishedDate = r.path("publishedDate").asString(null)?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                val feedItem = FeedItem(
                    id = UUID.randomUUID().toString(),
                    title = r.path("title").asString("").ifBlank { url },
                    summary = r.path("summary").asString(""),
                    url = url,
                    source = r.path("source").asString("").ifBlank { extractDomain(url) },
                    sourceUrls = listOf(url),
                    topics = listOf(current.subject),
                    feedReason = "Geselecteerd voor verzoek '${current.subject}'",
                    publishedDate = publishedDate,
                    createdAt = Instant.now()
                )
                feed.save(username, feedItem)
                newItems++
            }
            // Null-safe: de request kan tussentijds geannuleerd/verwijderd
            // zijn — voortgang bijwerken is dan niet meer nodig.
            repo.load(username).find { it.id == requestId }?.let { req ->
                service.upsert(
                    username,
                    req.copy(
                        newItemCount = newItems,
                        durationSeconds = ChronoUnit.SECONDS.between(started, Instant.now()).toInt()
                    )
                )
            }

            val finalReq = repo.load(username).find { it.id == requestId }?.copy(
                status = RequestStatus.DONE,
                completedAt = Instant.now(),
                durationSeconds = ChronoUnit.SECONDS.between(started, Instant.now()).toInt()
            )
            if (finalReq == null) {
                log.info("[Request] id={} verdween tijdens verwerking (geannuleerd/verwijderd) — geen eindstatus gezet", requestId)
                return
            }
            service.upsert(username, finalReq)
            meters.counter("newsfeed.requests.processed", "type", "adhoc", "status", "DONE").increment()
            log.info("[Request] done id={} items={}", requestId, newItems)
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

    companion object {
        private val SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["items"],"properties":{
              "items":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["title","url","source","publishedDate","summary"],"properties":{
                "title":{"type":"string"},"url":{"type":"string"},"source":{"type":"string"},
                "publishedDate":{"type":["string","null"]},"summary":{"type":"string"}}}}}}
        """.trimIndent()
    }
}
