package com.vdzon.newsfeedbackend.settings.infrastructure

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * KAN-68: per-user lijst met event-namen (seeds) die de gebruiker wil
 * laten volgen door de wekelijkse [com.vdzon.newsfeedbackend.events.domain.EventDiscoveryPipeline].
 *
 * Naam is de PK — duplicaten worden door de DB tegengehouden. sort_order
 * bewaart de volgorde waarin de gebruiker ze heeft toegevoegd.
 */
@Component
class EventPreferencesRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    fun load(username: String): List<String> =
        jdbc.queryForList(
            "SELECT name FROM event_preferences WHERE username = :u ORDER BY sort_order, name",
            MapSqlParameterSource("u", username),
            String::class.java
        )

    fun save(username: String, names: List<String>) {
        jdbc.update(
            "DELETE FROM event_preferences WHERE username = :u",
            MapSqlParameterSource("u", username)
        )
        names.forEachIndexed { idx, name ->
            jdbc.update(
                """
                INSERT INTO event_preferences (username, name, sort_order)
                VALUES (:u, :name, :sort)
                ON CONFLICT (username, name) DO UPDATE SET sort_order = EXCLUDED.sort_order
                """,
                MapSqlParameterSource()
                    .addValue("u", username)
                    .addValue("name", name)
                    .addValue("sort", idx)
            )
        }
    }

    fun add(username: String, name: String): Boolean {
        val n = jdbc.update(
            """
            INSERT INTO event_preferences (username, name, sort_order)
            SELECT :u, :name, COALESCE(MAX(sort_order) + 1, 0)
            FROM event_preferences WHERE username = :u
            ON CONFLICT (username, name) DO NOTHING
            """,
            MapSqlParameterSource()
                .addValue("u", username)
                .addValue("name", name)
        )
        return n > 0
    }

    fun remove(username: String, name: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM event_preferences WHERE username = :u AND name = :name",
            MapSqlParameterSource().addValue("u", username).addValue("name", name)
        )
        return n > 0
    }
}
