package com.vdzon.newsfeedbackend.admin.api

import com.vdzon.newsfeedbackend.admin.AdminCostsService
import com.vdzon.newsfeedbackend.admin.CostPeriod
import com.vdzon.newsfeedbackend.admin.DailyTotal
import com.vdzon.newsfeedbackend.admin.Totals
import com.vdzon.newsfeedbackend.admin.UserTotal
import com.vdzon.newsfeedbackend.common.BadRequestException
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/admin/costs")
class AdminCostsController(private val service: AdminCostsService) {

    @GetMapping("/totals")
    fun totals(): Totals = service.grandTotals()

    @GetMapping("/daily")
    fun daily(@RequestParam(defaultValue = "30") days: Int): List<DailyTotal> {
        if (days < 1 || days > 365) throw BadRequestException("days must be between 1 and 365")
        return service.dailyBreakdown(days)
    }

    @GetMapping("/by-user")
    fun byUser(@RequestParam(defaultValue = "this_month") period: String): List<UserTotal> {
        val parsed = when (period.lowercase()) {
            "this_month", "thismonth", "month" -> CostPeriod.THIS_MONTH
            "last_month", "lastmonth" -> CostPeriod.LAST_MONTH
            "this_year", "thisyear", "year" -> CostPeriod.THIS_YEAR
            "all" -> CostPeriod.ALL
            else -> throw BadRequestException("Unknown period '$period' (use this_month|last_month|this_year|all)")
        }
        return service.byUser(parsed)
    }

    @GetMapping("/calls")
    fun calls(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) user: String?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "500") limit: Int
    ): List<ExternalCall> = service.calls(
        from = from?.let { parseInstant(it) },
        to = to?.let { parseInstant(it) },
        username = user,
        provider = provider,
        action = action,
        status = status,
        limit = limit.coerceIn(1, 5000)
    )

    private fun parseInstant(s: String): Instant = try {
        Instant.parse(s)
    } catch (e: Exception) {
        throw BadRequestException("Invalid ISO-8601 timestamp: $s")
    }
}
