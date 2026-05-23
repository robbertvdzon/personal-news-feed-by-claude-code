package com.vdzon.newsfeedbackend.settings.infrastructure

import com.vdzon.newsfeedbackend.settings.EventDenylistEntry
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * KAN-68: per-user denylist met genormaliseerde event-ids (zelfde slug
 * als `events.id`, bv. `javaone-2026`). Een event-id op de denylist
 * wordt door de [com.vdzon.newsfeedbackend.events.domain.EventDiscoveryPipeline]
 * niet opnieuw aangemaakt; de gebruiker kan items uit de Settings-UI
 * weer van de denylist halen.
 */
@Component
class EventDenylistRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): EventDenylistEntry =
        EventDenylistEntry(
            normalizedId = rs.getString("normalized_id"),
            name = rs.getString("name"),
            addedAt = rs.getTimestamp("added_at").toInstant()
        )

    fun load(username: String): List<EventDenylistEntry> =
        jdbc.query(
            "SELECT * FROM event_denylist WHERE username = :u ORDER BY added_at DESC",
            MapSqlParameterSource("u", username),
            ::map
        )

    fun ids(username: String): Set<String> =
        jdbc.queryForList(
            "SELECT normalized_id FROM event_denylist WHERE username = :u",
            MapSqlParameterSource("u", username),
            String::class.java
        ).toSet()

    fun add(username: String, normalizedId: String, name: String): Boolean {
        val n = jdbc.update(
            """
            INSERT INTO event_denylist (username, normalized_id, name)
            VALUES (:u, :id, :name)
            ON CONFLICT (username, normalized_id) DO UPDATE SET name = EXCLUDED.name
            """,
            MapSqlParameterSource()
                .addValue("u", username)
                .addValue("id", normalizedId)
                .addValue("name", name)
        )
        return n > 0
    }

    fun remove(username: String, normalizedId: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM event_denylist WHERE username = :u AND normalized_id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", normalizedId)
        )
        return n > 0
    }
}
