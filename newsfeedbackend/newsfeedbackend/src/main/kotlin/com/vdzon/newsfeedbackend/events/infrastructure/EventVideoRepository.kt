package com.vdzon.newsfeedbackend.events.infrastructure

import com.vdzon.newsfeedbackend.events.EventVideo
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** KAN-66: opslag van per-event ontdekte video's. Dedup op (username, event_id, video_url). */
@Component
class EventVideoRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): EventVideo = EventVideo(
        eventId = rs.getString("event_id"),
        videoUrl = rs.getString("video_url"),
        title = rs.getString("title"),
        descriptionNl = rs.getString("description_nl"),
        summaryNl = rs.getString("summary_nl"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant()
    )

    private fun params(username: String, item: EventVideo) = MapSqlParameterSource()
        .addValue("username", username)
        .addValue("event_id", item.eventId)
        .addValue("video_url", item.videoUrl)
        .addValue("title", item.title)
        .addValue("description_nl", item.descriptionNl)
        .addValue("summary_nl", item.summaryNl)
        .addValue("created_at", Timestamp.from(item.createdAt))
        .addValue("updated_at", Timestamp.from(item.updatedAt))

    fun loadForEvent(username: String, eventId: String): List<EventVideo> =
        jdbc.query(
            "SELECT * FROM event_videos WHERE username = :u AND event_id = :e ORDER BY created_at DESC",
            MapSqlParameterSource().addValue("u", username).addValue("e", eventId),
            ::map
        )

    fun get(username: String, eventId: String, videoUrl: String): EventVideo? =
        jdbc.query(
            "SELECT * FROM event_videos WHERE username = :u AND event_id = :e AND video_url = :url",
            MapSqlParameterSource()
                .addValue("u", username)
                .addValue("e", eventId)
                .addValue("url", videoUrl),
            ::map
        ).firstOrNull()

    fun upsert(username: String, item: EventVideo): EventVideo {
        jdbc.update(UPSERT_SQL, params(username, item))
        return item
    }

    /**
     * KAN-67: vul (of overschrijf) de Nederlandse samenvatting voor één
     * video. Aparte update zodat de wekelijkse discovery-upsert deze
     * waarde niet per ongeluk op `EXCLUDED.summary_nl` zet (die zou bij
     * een tweede ontdekking altijd NULL zijn — Claude-call weggegooid).
     */
    fun setSummary(username: String, eventId: String, videoUrl: String, summaryNl: String): Boolean {
        val n = jdbc.update(
            """
            UPDATE event_videos
               SET summary_nl = :summary_nl,
                   updated_at = :updated_at
             WHERE username = :u AND event_id = :e AND video_url = :url
            """,
            MapSqlParameterSource()
                .addValue("u", username)
                .addValue("e", eventId)
                .addValue("url", videoUrl)
                .addValue("summary_nl", summaryNl)
                .addValue("updated_at", Timestamp.from(Instant.now()))
        )
        return n > 0
    }

    companion object {
        // BELANGRIJK (KAN-67): summary_nl staat bewust NIET in de UPDATE-
        // clause. Bij een nieuwe rij komt 'ie via INSERT (default NULL);
        // bij een tweede discovery van dezelfde video blijft de eerder
        // door [setSummary] geschreven samenvatting staan.
        private val UPSERT_SQL = """
            INSERT INTO event_videos (
                username, event_id, video_url, title, description_nl, summary_nl,
                created_at, updated_at
            ) VALUES (
                :username, :event_id, :video_url, :title, :description_nl, :summary_nl,
                :created_at, :updated_at
            )
            ON CONFLICT (username, event_id, video_url) DO UPDATE SET
                title          = EXCLUDED.title,
                description_nl = EXCLUDED.description_nl,
                updated_at     = EXCLUDED.updated_at
        """
    }
}
