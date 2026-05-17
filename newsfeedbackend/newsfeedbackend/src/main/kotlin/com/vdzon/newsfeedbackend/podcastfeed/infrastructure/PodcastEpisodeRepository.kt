package com.vdzon.newsfeedbackend.podcastfeed.infrastructure

import com.vdzon.newsfeedbackend.podcastfeed.EpisodeStatus
import com.vdzon.newsfeedbackend.podcastfeed.PodcastEpisode
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp

@Component
class PodcastEpisodeRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): PodcastEpisode = PodcastEpisode(
        guid = rs.getString("guid"),
        feedUrl = rs.getString("feed_url"),
        title = rs.getString("title"),
        podcastName = rs.getString("podcast_name"),
        audioUrl = rs.getString("audio_url"),
        durationSeconds = rs.getObject("duration_seconds") as? Int,
        description = rs.getString("description"),
        transcript = rs.getString("transcript"),
        summary = rs.getString("summary"),
        summarySource = rs.getString("summary_source"),
        status = EpisodeStatus.valueOf(rs.getString("status")),
        errorMessage = rs.getString("error_message"),
        publishedDate = rs.getString("published_date"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        processedAt = rs.getTimestamp("processed_at")?.toInstant(),
        feedItemId = rs.getString("feed_item_id")
    )

    private fun params(username: String, e: PodcastEpisode) = MapSqlParameterSource()
        .addValue("username", username)
        .addValue("guid", e.guid)
        .addValue("feed_url", e.feedUrl)
        .addValue("title", e.title)
        .addValue("podcast_name", e.podcastName)
        .addValue("audio_url", e.audioUrl)
        .addValue("duration_seconds", e.durationSeconds)
        .addValue("description", e.description)
        .addValue("transcript", e.transcript)
        .addValue("summary", e.summary)
        .addValue("summary_source", e.summarySource)
        .addValue("status", e.status.name)
        .addValue("error_message", e.errorMessage)
        .addValue("published_date", e.publishedDate)
        .addValue("created_at", Timestamp.from(e.createdAt))
        .addValue("processed_at", e.processedAt?.let { Timestamp.from(it) })
        .addValue("feed_item_id", e.feedItemId)

    fun load(username: String): List<PodcastEpisode> = jdbc.query(
        "SELECT * FROM podcast_episodes WHERE username = :u ORDER BY created_at DESC",
        MapSqlParameterSource("u", username),
        ::map
    )

    fun find(username: String, guid: String): PodcastEpisode? = jdbc.query(
        "SELECT * FROM podcast_episodes WHERE username = :u AND guid = :g",
        MapSqlParameterSource().addValue("u", username).addValue("g", guid),
        ::map
    ).firstOrNull()

    fun exists(username: String, guid: String): Boolean =
        find(username, guid) != null

    fun upsert(username: String, episode: PodcastEpisode): PodcastEpisode {
        jdbc.update(UPSERT_SQL, params(username, episode))
        return episode
    }

    /**
     * INSERT-only: voorkomt dat een refresh een DONE-episode terugzet op
     * PENDING. Returnt true als er werkelijk een rij is toegevoegd.
     * Gebruikt voor idempotency (AC6).
     */
    fun insertIfAbsent(username: String, episode: PodcastEpisode): Boolean {
        val n = jdbc.update(INSERT_IF_ABSENT_SQL, params(username, episode))
        return n > 0
    }

    fun delete(username: String, guid: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM podcast_episodes WHERE username = :u AND guid = :g",
            MapSqlParameterSource().addValue("u", username).addValue("g", guid)
        )
        return n > 0
    }

    fun findByFeed(username: String, feedUrl: String): List<PodcastEpisode> = jdbc.query(
        "SELECT * FROM podcast_episodes WHERE username = :u AND feed_url = :f ORDER BY created_at DESC",
        MapSqlParameterSource().addValue("u", username).addValue("f", feedUrl),
        ::map
    )

    /** Lijst van episodes die nog niet in DONE/FAILED staan — gebruikt door de pipeline. */
    fun findPending(username: String): List<PodcastEpisode> = jdbc.query(
        "SELECT * FROM podcast_episodes WHERE username = :u AND status NOT IN ('DONE','FAILED') ORDER BY created_at",
        MapSqlParameterSource("u", username),
        ::map
    )

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO podcast_episodes (
                username, guid, feed_url, title, podcast_name, audio_url,
                duration_seconds, description, transcript, summary,
                summary_source, status, error_message, published_date,
                created_at, processed_at, feed_item_id
            ) VALUES (
                :username, :guid, :feed_url, :title, :podcast_name, :audio_url,
                :duration_seconds, :description, :transcript, :summary,
                :summary_source, :status, :error_message, :published_date,
                :created_at, :processed_at, :feed_item_id
            )
            ON CONFLICT (username, guid) DO UPDATE SET
                feed_url         = EXCLUDED.feed_url,
                title            = EXCLUDED.title,
                podcast_name     = EXCLUDED.podcast_name,
                audio_url        = EXCLUDED.audio_url,
                duration_seconds = EXCLUDED.duration_seconds,
                description      = EXCLUDED.description,
                transcript       = EXCLUDED.transcript,
                summary          = EXCLUDED.summary,
                summary_source   = EXCLUDED.summary_source,
                status           = EXCLUDED.status,
                error_message    = EXCLUDED.error_message,
                published_date   = EXCLUDED.published_date,
                processed_at     = EXCLUDED.processed_at,
                feed_item_id     = EXCLUDED.feed_item_id
        """

        private val INSERT_IF_ABSENT_SQL = """
            INSERT INTO podcast_episodes (
                username, guid, feed_url, title, podcast_name, audio_url,
                duration_seconds, description, transcript, summary,
                summary_source, status, error_message, published_date,
                created_at, processed_at, feed_item_id
            ) VALUES (
                :username, :guid, :feed_url, :title, :podcast_name, :audio_url,
                :duration_seconds, :description, :transcript, :summary,
                :summary_source, :status, :error_message, :published_date,
                :created_at, :processed_at, :feed_item_id
            )
            ON CONFLICT (username, guid) DO NOTHING
        """
    }
}
