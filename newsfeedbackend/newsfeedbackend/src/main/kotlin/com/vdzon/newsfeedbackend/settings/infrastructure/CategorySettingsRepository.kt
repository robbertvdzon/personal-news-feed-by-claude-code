package com.vdzon.newsfeedbackend.settings.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

interface CategorySettingsRepository {
    fun load(username: String): List<CategorySettings>
    fun save(username: String, categories: List<CategorySettings>)
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonCategorySettingsRepository(private val store: JsonStore) : CategorySettingsRepository {
    private fun file(u: String) = store.userFile(u, "settings.json")

    override fun load(username: String): List<CategorySettings> =
        store.readJsonRef(file(username), object : TypeReference<List<CategorySettings>>() {}, emptyList())

    override fun save(username: String, categories: List<CategorySettings>) {
        store.writeJson(file(username), categories)
    }
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "postgres")
class PostgresCategorySettingsRepository(
    private val jdbc: NamedParameterJdbcTemplate
) : CategorySettingsRepository {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): CategorySettings = CategorySettings(
        id = rs.getString("id"),
        name = rs.getString("name"),
        enabled = rs.getBoolean("enabled"),
        extraInstructions = rs.getString("extra_instructions"),
        isSystem = rs.getBoolean("is_system")
    )

    override fun load(username: String): List<CategorySettings> =
        jdbc.query(
            "SELECT * FROM category_settings WHERE username = :u ORDER BY sort_order, id",
            MapSqlParameterSource("u", username),
            ::map
        )

    override fun save(username: String, categories: List<CategorySettings>) {
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
