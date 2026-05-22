package com.vdzon.newsfeedbackend.events.infrastructure

import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.storage.JdbcJsonb
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp

@Component
class EventRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val json: JdbcJsonb
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): Event = Event(
        id = rs.getString("id"),
        name = rs.getString("name"),
        organization = rs.getString("organization"),
        startDate = rs.getString("start_date"),
        endDate = rs.getString("end_date"),
        location = rs.getString("location"),
        description = rs.getString("description"),
        sourceLinks = json.readList(rs, "source_links", String::class.java),
        category = rs.getString("category"),
        feedItemId = rs.getString("feed_item_id"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant()
    )

    private fun params(username: String, item: Event) = MapSqlParameterSource()
        .addValue("username", username)
        .addValue("id", item.id)
        .addValue("name", item.name)
        .addValue("organization", item.organization)
        .addValue("start_date", item.startDate)
        .addValue("end_date", item.endDate)
        .addValue("location", item.location)
        .addValue("description", item.description)
        .addValue("source_links", json.toJsonb(item.sourceLinks))
        .addValue("category", item.category)
        .addValue("feed_item_id", item.feedItemId)
        .addValue("created_at", Timestamp.from(item.createdAt))
        .addValue("updated_at", Timestamp.from(item.updatedAt))

    fun load(username: String): MutableList<Event> =
        jdbc.query(
            "SELECT * FROM events WHERE username = :u ORDER BY start_date DESC NULLS LAST",
            MapSqlParameterSource("u", username),
            ::map
        ).toMutableList()

    fun upsert(username: String, item: Event): Event {
        jdbc.update(UPSERT_SQL, params(username, item))
        return item
    }

    fun delete(username: String, id: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM events WHERE username = :u AND id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", id)
        )
        return n > 0
    }

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO events (
                username, id, name, organization, start_date, end_date,
                location, description, source_links, category, feed_item_id,
                created_at, updated_at
            ) VALUES (
                :username, :id, :name, :organization, :start_date, :end_date,
                :location, :description, :source_links, :category, :feed_item_id,
                :created_at, :updated_at
            )
            ON CONFLICT (username, id) DO UPDATE SET
                name         = EXCLUDED.name,
                organization = EXCLUDED.organization,
                start_date   = EXCLUDED.start_date,
                end_date     = EXCLUDED.end_date,
                location     = EXCLUDED.location,
                description  = EXCLUDED.description,
                source_links = EXCLUDED.source_links,
                category     = EXCLUDED.category,
                feed_item_id = EXCLUDED.feed_item_id,
                updated_at   = EXCLUDED.updated_at
        """
    }
}
