package com.vdzon.newsfeedbackend.external_call.infrastructure

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Audit/cost-log voor externe API-calls. Append-only via INSERT (met
 * ON CONFLICT DO NOTHING zodat een retry niet duplicate inserts geeft).
 * Query met dynamische WHERE-clausules over periode/user/provider/etc.
 */
@Component
class ExternalCallRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): ExternalCall = ExternalCall(
        id = rs.getString("id"),
        provider = rs.getString("provider"),
        action = rs.getString("action"),
        username = rs.getString("username") ?: "",
        startTime = rs.getTimestamp("start_time").toInstant(),
        endTime = rs.getTimestamp("end_time").toInstant(),
        durationMs = rs.getLong("duration_ms"),
        tokensIn = rs.getObject("tokens_in") as? Long,
        tokensOut = rs.getObject("tokens_out") as? Long,
        units = rs.getObject("units") as? Long,
        unitType = rs.getString("unit_type"),
        costUsd = rs.getDouble("cost_usd"),
        status = rs.getString("status"),
        errorMessage = rs.getString("error_message"),
        subject = rs.getString("subject")
    )

    fun append(call: ExternalCall) {
        jdbc.update(
            """
            INSERT INTO external_calls (
                id, username, provider, action, start_time, end_time,
                duration_ms, tokens_in, tokens_out, units, unit_type,
                cost_usd, status, error_message, subject
            ) VALUES (
                :id, :username, :provider, :action, :start_time, :end_time,
                :duration_ms, :tokens_in, :tokens_out, :units, :unit_type,
                :cost_usd, :status, :error_message, :subject
            )
            ON CONFLICT (id) DO NOTHING
            """,
            MapSqlParameterSource()
                .addValue("id", call.id)
                .addValue("username", call.username)
                .addValue("provider", call.provider)
                .addValue("action", call.action)
                .addValue("start_time", Timestamp.from(call.startTime))
                .addValue("end_time", Timestamp.from(call.endTime))
                .addValue("duration_ms", call.durationMs)
                .addValue("tokens_in", call.tokensIn)
                .addValue("tokens_out", call.tokensOut)
                .addValue("units", call.units)
                .addValue("unit_type", call.unitType)
                .addValue("cost_usd", call.costUsd)
                .addValue("status", call.status)
                .addValue("error_message", call.errorMessage)
                .addValue("subject", call.subject)
        )
    }

    fun query(
        from: Instant? = null,
        to: Instant? = null,
        username: String? = null,
        provider: String? = null,
        action: String? = null,
        status: String? = null
    ): List<ExternalCall> {
        val sql = StringBuilder("SELECT * FROM external_calls WHERE 1=1")
        val params = MapSqlParameterSource()
        if (from != null) { sql.append(" AND start_time >= :from"); params.addValue("from", Timestamp.from(from)) }
        if (to != null) { sql.append(" AND start_time < :to"); params.addValue("to", Timestamp.from(to)) }
        if (username != null) { sql.append(" AND username = :username"); params.addValue("username", username) }
        if (provider != null) { sql.append(" AND provider = :provider"); params.addValue("provider", provider) }
        if (action != null) { sql.append(" AND action = :action"); params.addValue("action", action) }
        if (status != null) { sql.append(" AND status = :status"); params.addValue("status", status) }
        sql.append(" ORDER BY start_time DESC")
        return jdbc.query(sql.toString(), params, ::map)
    }

    fun all(): List<ExternalCall> = query()
}
