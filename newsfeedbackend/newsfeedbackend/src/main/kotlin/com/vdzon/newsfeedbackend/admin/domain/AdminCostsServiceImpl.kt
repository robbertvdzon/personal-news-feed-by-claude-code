package com.vdzon.newsfeedbackend.admin.domain

import com.vdzon.newsfeedbackend.admin.AdminCostsService
import com.vdzon.newsfeedbackend.admin.CostPeriod
import com.vdzon.newsfeedbackend.admin.DailyTotal
import com.vdzon.newsfeedbackend.admin.Totals
import com.vdzon.newsfeedbackend.admin.UserTotal
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.infrastructure.ExternalCallRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@Service
class AdminCostsServiceImpl(
    private val repo: ExternalCallRepository
) : AdminCostsService {

    override fun grandTotals(): Totals {
        val all = repo.all()
        val now = Instant.now()
        val startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)
        val startOfMonth = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val startOfYear = LocalDate.now(ZoneOffset.UTC).withDayOfYear(1).atStartOfDay().toInstant(ZoneOffset.UTC)

        val today = all.filter { !it.startTime.isBefore(startOfToday) && it.startTime.isBefore(now) }.sumOf { it.costUsd }
        val month = all.filter { !it.startTime.isBefore(startOfMonth) }.sumOf { it.costUsd }
        val year = all.filter { !it.startTime.isBefore(startOfYear) }.sumOf { it.costUsd }
        val total = all.sumOf { it.costUsd }
        return Totals(today, month, year, total, all.size)
    }

    override fun dailyBreakdown(days: Int): List<DailyTotal> {
        val from = LocalDate.now(ZoneOffset.UTC).minusDays((days - 1).toLong())
            .atStartOfDay().toInstant(ZoneOffset.UTC)
        val all = repo.query(from = from)
        // Pre-build alle dagen zodat dagen zonder calls óók in het overzicht
        // staan met 0,00 — anders zie je gaten en lijken bv. weekenden te
        // missen wat verwarrend is.
        val byDay = (0 until days).map { offset ->
            val date = LocalDate.now(ZoneOffset.UTC).minusDays(offset.toLong())
            val dayStart = date.atStartOfDay().toInstant(ZoneOffset.UTC)
            val dayEnd = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            val calls = all.filter { !it.startTime.isBefore(dayStart) && it.startTime.isBefore(dayEnd) }
            DailyTotal(
                date = date.toString(),
                total = calls.sumOf { it.costUsd },
                byProvider = providerBreakdown(calls),
                callCount = calls.size
            )
        }
        return byDay
    }

    override fun byUser(period: CostPeriod): List<UserTotal> {
        val from = startOfPeriod(period)
        val all = if (from == null) repo.all() else repo.query(from = from)
        return all.groupBy { it.username }
            .map { (username, calls) ->
                UserTotal(
                    username = username,
                    total = calls.sumOf { it.costUsd },
                    byProvider = providerBreakdown(calls),
                    callCount = calls.size
                )
            }
            .sortedByDescending { it.total }
    }

    override fun calls(
        from: Instant?,
        to: Instant?,
        username: String?,
        provider: String?,
        action: String?,
        status: String?,
        limit: Int
    ): List<ExternalCall> = repo.query(
        from = from,
        to = to,
        username = username,
        provider = provider,
        action = action,
        status = status
    ).take(limit)

    private fun startOfPeriod(period: CostPeriod): Instant? {
        val today = LocalDate.now(ZoneOffset.UTC)
        return when (period) {
            CostPeriod.THIS_MONTH -> YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            CostPeriod.LAST_MONTH -> YearMonth.now(ZoneOffset.UTC).minusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            CostPeriod.THIS_YEAR -> today.withDayOfYear(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            CostPeriod.ALL -> null
        }
    }

    private fun providerBreakdown(calls: List<ExternalCall>): Map<String, Double> {
        val providers = listOf(
            ExternalCall.PROVIDER_ANTHROPIC,
            ExternalCall.PROVIDER_OPENAI,
            ExternalCall.PROVIDER_ELEVENLABS,
            ExternalCall.PROVIDER_TAVILY
        )
        // Vaste kolomvolgorde + altijd alle providers (ook 0,00) zodat het
        // overzicht visueel stabiel blijft.
        return providers.associateWith { p -> calls.filter { it.provider == p }.sumOf { it.costUsd } }
    }
}
