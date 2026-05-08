package com.vdzon.newsfeedbackend.request.domain

import com.vdzon.newsfeedbackend.auth.AuthService
import com.vdzon.newsfeedbackend.common.NotFoundException
import com.vdzon.newsfeedbackend.request.CreateRequestDto
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestCreatedEvent
import com.vdzon.newsfeedbackend.request.RequestRerunEvent
import com.vdzon.newsfeedbackend.request.RequestService
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.request.infrastructure.RequestRepository
import com.vdzon.newsfeedbackend.websocket.RequestWebSocketHandler
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class RequestServiceImpl(
    private val repo: RequestRepository,
    private val auth: AuthService,
    private val ws: RequestWebSocketHandler,
    private val events: ApplicationEventPublisher
) : RequestService {

    private val log = LoggerFactory.getLogger(javaClass)
    val cancellation = ConcurrentHashMap<String, Boolean>()

    override fun list(username: String): List<NewsRequest> = repo.load(username)

    override fun get(username: String, id: String): NewsRequest? = repo.load(username).find { it.id == id }

    override fun create(username: String, dto: CreateRequestDto): NewsRequest {
        val req = NewsRequest(
            id = UUID.randomUUID().toString(),
            subject = dto.subject,
            sourceItemId = dto.sourceItemId,
            sourceItemTitle = dto.sourceItemTitle,
            preferredCount = dto.preferredCount,
            maxCount = dto.maxCount,
            extraInstructions = dto.extraInstructions,
            maxAgeDays = dto.maxAgeDays,
            status = RequestStatus.PENDING,
            createdAt = Instant.now()
        )
        val saved = repo.upsert(username, req)
        ws.broadcast(saved)
        events.publishEvent(RequestCreatedEvent(username, saved.id))
        return saved
    }

    override fun delete(username: String, id: String): Boolean {
        if (id.startsWith("daily-update-") || id.startsWith("daily-summary-")) return false
        return repo.delete(username, id)
    }

    override fun cancel(username: String, id: String): Boolean {
        cancellation[id] = true
        val req = get(username, id) ?: return false
        if (req.status == RequestStatus.PENDING || req.status == RequestStatus.PROCESSING) {
            upsert(username, req.copy(status = RequestStatus.CANCELLED, completedAt = Instant.now()))
        }
        return true
    }

    override fun rerun(username: String, id: String): NewsRequest? {
        val req = get(username, id) ?: throw NotFoundException("request $id")
        cancellation.remove(id)
        val reset = req.copy(
            status = RequestStatus.PENDING,
            completedAt = null,
            processingStartedAt = null,
            durationSeconds = 0,
            categoryResults = emptyList(),
            newItemCount = 0,
            costUsd = 0.0
        )
        val saved = upsert(username, reset)
        events.publishEvent(RequestRerunEvent(username, id))
        return saved
    }

    override fun upsert(username: String, request: NewsRequest): NewsRequest {
        val saved = repo.upsert(username, request)
        ws.broadcast(saved)
        return saved
    }

    override fun ensureFixedRequests(username: String) {
        val all = repo.load(username)
        val fixed = listOf(
            "daily-update-$username" to "Dagelijkse update",
            "daily-summary-$username" to "Dagelijkse samenvatting"
        )
        var changed = false
        fixed.forEach { (id, subject) ->
            if (all.none { it.id == id }) {
                all.add(
                    NewsRequest(
                        id = id,
                        subject = subject,
                        status = RequestStatus.DONE,
                        isDailyUpdate = id.startsWith("daily-update-"),
                        isDailySummary = id.startsWith("daily-summary-")
                    )
                )
                changed = true
            }
        }
        if (changed) repo.save(username, all)
    }

    override fun resetStuck() {
        auth.listUsernames().forEach { username ->
            val all = repo.load(username)
            var changed = false
            for (i in all.indices) {
                if (all[i].status == RequestStatus.PENDING || all[i].status == RequestStatus.PROCESSING) {
                    all[i] = all[i].copy(status = RequestStatus.FAILED, completedAt = Instant.now())
                    changed = true
                }
            }
            if (changed) {
                repo.save(username, all)
                log.info("Reset stuck requests for user '{}'", username)
            }
        }
    }

    fun isCancelled(id: String): Boolean = cancellation[id] == true
}
