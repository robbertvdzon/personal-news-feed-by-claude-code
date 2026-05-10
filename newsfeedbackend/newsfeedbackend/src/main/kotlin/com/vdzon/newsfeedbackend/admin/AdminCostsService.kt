package com.vdzon.newsfeedbackend.admin

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import java.time.Instant

/**
 * Aggregations bovenop de [ExternalCall]-log voor het admin-kosten-scherm.
 */
interface AdminCostsService {
    fun grandTotals(): Totals
    fun dailyBreakdown(days: Int): List<DailyTotal>
    fun byUser(period: CostPeriod): List<UserTotal>
    fun calls(
        from: Instant?,
        to: Instant?,
        username: String?,
        provider: String?,
        action: String?,
        status: String?,
        limit: Int = 500
    ): List<ExternalCall>
}

enum class CostPeriod { THIS_MONTH, LAST_MONTH, THIS_YEAR, ALL }

/** Totalen voor de header-cards (top of screen). */
data class Totals(
    val today: Double,
    val thisMonth: Double,
    val thisYear: Double,
    val all: Double,
    val callCountAll: Int
)

data class DailyTotal(
    val date: String,             // ISO-8601 (UTC)
    val total: Double,
    val byProvider: Map<String, Double>,
    val callCount: Int
)

data class UserTotal(
    val username: String,
    val total: Double,
    val byProvider: Map<String, Double>,
    val callCount: Int
)
