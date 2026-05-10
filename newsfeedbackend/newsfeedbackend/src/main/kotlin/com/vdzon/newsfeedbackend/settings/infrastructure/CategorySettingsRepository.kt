package com.vdzon.newsfeedbackend.settings.infrastructure

import com.vdzon.newsfeedbackend.settings.CategorySettings
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

@Component
class CategorySettingsRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): CategorySettings = CategorySettings(
        id = rs.getString("id"),
        name = rs.getString("name"),
        enabled = rs.getBoolean("enabled"),
        extraInstructions = rs.getString("extra_instructions"),
        isSystem = rs.getBoolean("is_system")
    )

    fun load(username: String): List<CategorySettings> =
        jdbc.query(
            "SELECT * FROM category_settings WHERE username = :u ORDER BY sort_order, id",
            MapSqlParameterSource("u", username),
            ::map
        )

    fun save(username: String, categories: List<CategorySettings>) {
        jdbc.update(
            "DELETE FROM category_settings WHERE username = :u",
            MapSqlParameterSource("u", username)
        )
        categories.forEachIndexed { idx, cat ->
            jdbc.update(
                """
                INSERT INTO category_settings (
                    username, id, name, enabled, extra_instructions, is_system, sort_order
                ) VALUES (
                    :u, :id, :name, :enabled, :extra, :is_system, :sort
                )
                """,
                MapSqlParameterSource()
                    .addValue("u", username)
                    .addValue("id", cat.id)
                    .addValue("name", cat.name)
                    .addValue("enabled", cat.enabled)
                    .addValue("extra", cat.extraInstructions)
                    .addValue("is_system", cat.isSystem)
                    .addValue("sort", idx)
            )
        }
    }
}
