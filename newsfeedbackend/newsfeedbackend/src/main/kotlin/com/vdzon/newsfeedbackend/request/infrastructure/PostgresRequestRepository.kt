package com.vdzon.newsfeedbackend.request.infrastructure

import com.vdzon.newsfeedbackend.request.CategoryResult
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestStatus
import com.vdzon.newsfeedbackend.storage.JdbcJsonb
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "postgres")
class PostgresRequestRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val json: JdbcJsonb
) : RequestRepository {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): NewsRequest = NewsRequest(
        id = rs.getString("id"),
        subject = rs.getString("subject"),
        sourceItemId = rs.getString("source_item_id"),
        sourceItemTitle = rs.getString("source_item_title"),
        preferredCount = rs.getInt("preferred_count"),
        maxCount = rs.getInt("max_count"),
        extraInstructions = rs.getString("extra_instructions"),
        maxAgeDays = rs.getInt("max_age_days"),
        status = RequestStatus.valueOf(rs.getString("status")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        completedAt = rs.getTimestamp("completed_at")?.toInstant(),
        newItemCount = rs.getInt("new_item_count"),
        isHourlyUpdate = rs.getBoolean("is_hourly_update"),
        isDailySummary = rs.getBoolean("is_daily_summary"),
        categoryResults = json.readList(rs, "category_results", CategoryResult::class.java),
        processingStartedAt = rs.getTimestamp("processing_started_at")?.toInstant(),
        durationSeconds = rs.getInt("duration_seconds")
    )

    private fun params(username: String, r: NewsRequest) = MapSqlParameterSource()
        .addValue("username", username)
        .addValue("id", r.id)
        .addValue("subject", r.subject)
        .addValue("source_item_id", r.sourceItemId)
        .addValue("source_item_title", r.sourceItemTitle)
        .addValue("preferred_count", r.preferredCount)
        .addValue("max_count", r.maxCount)
        .addValue("extra_instructions", r.extraInstructions)
        .addValue("max_age_days", r.maxAgeDays)
        .addValue("status", r.status.name)
        .addValue("created_at", Timestamp.from(r.createdAt))
        .addValue("completed_at", r.completedAt?.let { Timestamp.from(it) })
        .addValue("new_item_count", r.newItemCount)
        .addValue("is_hourly_update", r.isHourlyUpdate)
        .addValue("is_daily_summary", r.isDailySummary)
        .addValue("category_results", json.toJsonb(r.categoryResults))
        .addValue("processing_started_at", r.processingStartedAt?.let { Timestamp.from(it) })
        .addValue("duration_seconds", r.durationSeconds)

    override fun load(username: String): MutableList<NewsRequest> =
        jdbc.query(
            "SELECT * FROM news_requests WHERE username = :u ORDER BY created_at DESC",
            MapSqlParameterSource("u", username),
            ::map
        ).toMutableList()

    override fun save(username: String, all: List<NewsRequest>) {
        jdbc.update("DELETE FROM news_requests WHERE username = :u", MapSqlParameterSource("u", username))
        all.forEach { upsert(username, it) }
    }

    override fun upsert(username: String, request: NewsRequest): NewsRequest {
        jdbc.update(UPSERT_SQL, params(username, request))
        return request
    }

    override fun delete(username: String, id: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM news_requests WHERE username = :u AND id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", id)
        )
        return n > 0
    }

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO news_requests (
                username, id, subject, source_item_id, source_item_title,
                preferred_count, max_count, extra_instructions, max_age_days,
                status, created_at, completed_at, new_item_count,
                is_hourly_update, is_daily_summary, category_results,
                processing_started_at, duration_seconds
            ) VALUES (
                :username, :id, :subject, :source_item_id, :source_item_title,
                :preferred_count, :max_count, :extra_instructions, :max_age_days,
                :status, :created_at, :completed_at, :new_item_count,
                :is_hourly_update, :is_daily_summary, :category_results,
                :processing_started_at, :duration_seconds
            )
            ON CONFLICT (username, id) DO UPDATE SET
                subject               = EXCLUDED.subject,
                source_item_id        = EXCLUDED.source_item_id,
                source_item_title     = EXCLUDED.source_item_title,
                preferred_count       = EXCLUDED.preferred_count,
                max_count             = EXCLUDED.max_count,
                extra_instructions    = EXCLUDED.extra_instructions,
                max_age_days          = EXCLUDED.max_age_days,
                status                = EXCLUDED.status,
                created_at            = EXCLUDED.created_at,
                completed_at          = EXCLUDED.completed_at,
                new_item_count        = EXCLUDED.new_item_count,
                is_hourly_update      = EXCLUDED.is_hourly_update,
                is_daily_summary      = EXCLUDED.is_daily_summary,
                category_results      = EXCLUDED.category_results,
                processing_started_at = EXCLUDED.processing_started_at,
                duration_seconds      = EXCLUDED.duration_seconds
        """
    }
}
