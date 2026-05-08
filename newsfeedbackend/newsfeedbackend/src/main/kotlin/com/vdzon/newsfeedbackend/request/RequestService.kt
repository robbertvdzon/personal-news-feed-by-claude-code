package com.vdzon.newsfeedbackend.request

import java.time.Instant

interface RequestService {
    fun list(username: String): List<NewsRequest>
    fun get(username: String, id: String): NewsRequest?
    fun create(username: String, dto: CreateRequestDto): NewsRequest
    fun delete(username: String, id: String): Boolean
    fun cancel(username: String, id: String): Boolean
    fun rerun(username: String, id: String): NewsRequest?
    fun upsert(username: String, request: NewsRequest): NewsRequest
    fun ensureFixedRequests(username: String)
    fun resetStuck()
}

data class CreateRequestDto(
    val subject: String,
    val sourceItemId: String? = null,
    val sourceItemTitle: String? = null,
    val preferredCount: Int = 2,
    val maxCount: Int = 5,
    val extraInstructions: String = "",
    val maxAgeDays: Int = 3
)

data class NewsRequest(
    val id: String,
    val subject: String,
    val sourceItemId: String? = null,
    val sourceItemTitle: String? = null,
    val preferredCount: Int = 2,
    val maxCount: Int = 5,
    val extraInstructions: String = "",
    val maxAgeDays: Int = 3,
    val status: RequestStatus = RequestStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val newItemCount: Int = 0,
    val costUsd: Double = 0.0,
    @get:com.fasterxml.jackson.annotation.JsonProperty("isDailyUpdate")
    @field:com.fasterxml.jackson.annotation.JsonProperty("isDailyUpdate")
    @param:com.fasterxml.jackson.annotation.JsonProperty("isDailyUpdate")
    val isDailyUpdate: Boolean = false,
    @get:com.fasterxml.jackson.annotation.JsonProperty("isDailySummary")
    @field:com.fasterxml.jackson.annotation.JsonProperty("isDailySummary")
    @param:com.fasterxml.jackson.annotation.JsonProperty("isDailySummary")
    val isDailySummary: Boolean = false,
    val categoryResults: List<CategoryResult> = emptyList(),
    val processingStartedAt: Instant? = null,
    val durationSeconds: Int = 0
)

enum class RequestStatus { PENDING, PROCESSING, DONE, FAILED, CANCELLED }

data class CategoryResult(
    val categoryId: String,
    val categoryName: String,
    val articleCount: Int = 0,
    val costUsd: Double = 0.0,
    val searchResultCount: Int = 0,
    val filteredCount: Int = 0
)
