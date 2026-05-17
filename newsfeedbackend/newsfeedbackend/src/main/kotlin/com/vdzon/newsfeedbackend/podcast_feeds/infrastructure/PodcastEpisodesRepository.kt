package com.vdzon.newsfeedbackend.podcast_feeds.infrastructure

import com.vdzon.newsfeedbackend.podcast_feeds.PodcastEpisode
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PodcastEpisodesRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    fun load(username: String): List<PodcastEpisode> {
        return jdbc.query(
            """SELECT guid, feed_url, title, description, status, podcast_url, transcript, show_notes,
                      feed_item_id, created_at, updated_at, completed_at, error_message
               FROM podcast_episodes WHERE username = :u ORDER BY created_at DESC""",
            MapSqlParameterSource("u", username)
        ) { rs, _ -> mapRow(rs) }
    }

    fun loadByStatus(username: String, status: String): List<PodcastEpisode> {
        return jdbc.query(
            """SELECT guid, feed_url, title, description, status, podcast_url, transcript, show_notes,
                      feed_item_id, created_at, updated_at, completed_at, error_message
               FROM podcast_episodes WHERE username = :u AND status = :status ORDER BY created_at ASC""",
            MapSqlParameterSource("u", username).addValue("status", status)
        ) { rs, _ -> mapRow(rs) }
    }

    fun get(username: String, guid: String): PodcastEpisode? {
        return jdbc.query(
            """SELECT guid, feed_url, title, description, status, podcast_url, transcript, show_notes,
                      feed_item_id, created_at, updated_at, completed_at, error_message
               FROM podcast_episodes WHERE username = :u AND guid = :guid""",
            MapSqlParameterSource("u", username).addValue("guid", guid)
        ) { rs, _ -> mapRow(rs) }.firstOrNull()
    }

    fun upsert(username: String, episode: PodcastEpisode) {
        jdbc.update(
            """INSERT INTO podcast_episodes
               (username, guid, feed_url, title, description, status, podcast_url, transcript, show_notes,
                feed_item_id, created_at, updated_at, completed_at, error_message)
               VALUES (:u, :guid, :feedUrl, :title, :description, :status, :podcastUrl, :transcript, :showNotes,
                       :feedItemId, :createdAt, :updatedAt, :completedAt, :errorMessage)
               ON CONFLICT (username, guid) DO UPDATE SET
                   status = EXCLUDED.status,
                   podcast_url = EXCLUDED.podcast_url,
                   transcript = EXCLUDED.transcript,
                   show_notes = EXCLUDED.show_notes,
                   feed_item_id = EXCLUDED.feed_item_id,
                   updated_at = EXCLUDED.updated_at,
                   completed_at = EXCLUDED.completed_at,
                   error_message = EXCLUDED.error_message
            """,
            MapSqlParameterSource()
                .addValue("u", username)
                .addValue("guid", episode.guid)
                .addValue("feedUrl", episode.feedUrl)
                .addValue("title", episode.title)
                .addValue("description", episode.description)
                .addValue("status", episode.status)
                .addValue("podcastUrl", episode.podcastUrl)
                .addValue("transcript", episode.transcript)
                .addValue("showNotes", episode.showNotes)
                .addValue("feedItemId", episode.feedItemId)
                .addValue("createdAt", episode.createdAt)
                .addValue("updatedAt", Instant.now())
                .addValue("completedAt", episode.completedAt)
                .addValue("errorMessage", episode.errorMessage)
        )
    }

    private fun mapRow(rs: java.sql.ResultSet): PodcastEpisode = PodcastEpisode(
        guid = rs.getString("guid"),
        feedUrl = rs.getString("feed_url"),
        title = rs.getString("title"),
        description = rs.getString("description") ?: "",
        status = rs.getString("status"),
        podcastUrl = rs.getString("podcast_url"),
        transcript = rs.getString("transcript"),
        showNotes = rs.getString("show_notes"),
        feedItemId = rs.getString("feed_item_id"),
        createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.now(),
        updatedAt = rs.getTimestamp("updated_at")?.toInstant() ?: Instant.now(),
        completedAt = rs.getTimestamp("completed_at")?.toInstant(),
        errorMessage = rs.getString("error_message")
    )
}
