package com.vdzon.newsfeedbackend.podcast_source.infrastructure

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Component
class PodcastEpisodeRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): PodcastEpisode = PodcastEpisode(
        username = rs.getString("username"),
        guid = rs.getString("guid"),
        feedUrl = rs.getString("feed_url"),
        podcastName = rs.getString("podcast_name") ?: "",
        title = rs.getString("title") ?: "",
        audioUrl = rs.getString("audio_url") ?: "",
        durationSeconds = rs.getObject("duration_seconds") as? Int,
        publishedDate = rs.getString("published_date"),
        showNotes = rs.getString("show_notes") ?: "",
        transcript = rs.getString("transcript") ?: "",
        summary = rs.getString("summary") ?: "",
        status = runCatching {
            PodcastEpisodeStatus.valueOf(rs.getString("status") ?: "PENDING")
        }.getOrDefault(PodcastEpisodeStatus.PENDING),
        errorMessage = rs.getString("error_message"),
        rssItemId = rs.getString("rss_item_id"),
        createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.now(),
        updatedAt = rs.getTimestamp("updated_at")?.toInstant() ?: Instant.now()
    )

    fun load(username: String): List<PodcastEpisode> =
        jdbc.query(
            "SELECT * FROM podcast_episodes WHERE username = :u ORDER BY created_at DESC",
            MapSqlParameterSource("u", username),
            ::map
        )

    fun get(username: String, guid: String): PodcastEpisode? =
        jdbc.query(
            "SELECT * FROM podcast_episodes WHERE username = :u AND guid = :g",
            MapSqlParameterSource().addValue("u", username).addValue("g", guid),
            ::map
        ).firstOrNull()

    fun existingGuids(username: String, feedUrl: String): Set<String> =
        jdbc.queryForList(
            "SELECT guid FROM podcast_episodes WHERE username = :u AND feed_url = :f",
            MapSqlParameterSource().addValue("u", username).addValue("f", feedUrl),
            String::class.java
        ).toSet()

    fun countForFeed(username: String, feedUrl: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM podcast_episodes WHERE username = :u AND feed_url = :f",
            MapSqlParameterSource().addValue("u", username).addValue("f", feedUrl),
            Int::class.java
        ) ?: 0

    fun upsert(ep: PodcastEpisode): PodcastEpisode {
        val withTs = ep.copy(updatedAt = Instant.now())
        jdbc.update(UPSERT_SQL, params(withTs))
        return withTs
    }

    fun deleteForFeed(username: String, feedUrl: String): Int =
        jdbc.update(
            "DELETE FROM podcast_episodes WHERE username = :u AND feed_url = :f",
            MapSqlParameterSource().addValue("u", username).addValue("f", feedUrl)
        )

    fun resetFailedWithOomError(): Int =
        jdbc.update(
            """UPDATE podcast_episodes
               SET status = :status, error_message = NULL, updated_at = NOW()
               WHERE status = 'FAILED'
               AND (error_message LIKE '%heap space%' OR error_message = 'Audio-download faalde')""",
            MapSqlParameterSource("status", "PENDING")
        )

    private fun params(ep: PodcastEpisode) = MapSqlParameterSource()
        .addValue("username", ep.username)
        .addValue("guid", ep.guid)
        .addValue("feed_url", ep.feedUrl)
        .addValue("podcast_name", ep.podcastName)
        .addValue("title", ep.title)
        .addValue("audio_url", ep.audioUrl)
        .addValue("duration_seconds", ep.durationSeconds)
        .addValue("published_date", ep.publishedDate)
        .addValue("show_notes", ep.showNotes)
        .addValue("transcript", ep.transcript)
        .addValue("summary", ep.summary)
        .addValue("status", ep.status.name)
        .addValue("error_message", ep.errorMessage)
        .addValue("rss_item_id", ep.rssItemId)
        .addValue("created_at", Timestamp.from(ep.createdAt))
        .addValue("updated_at", Timestamp.from(ep.updatedAt))

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO podcast_episodes (
                username, guid, feed_url, podcast_name, title, audio_url,
                duration_seconds, published_date, show_notes, transcript,
                summary, status, error_message, rss_item_id, created_at, updated_at
            ) VALUES (
                :username, :guid, :feed_url, :podcast_name, :title, :audio_url,
                :duration_seconds, :published_date, :show_notes, :transcript,
                :summary, :status, :error_message, :rss_item_id, :created_at, :updated_at
            )
            ON CONFLICT (username, guid) DO UPDATE SET
                feed_url         = EXCLUDED.feed_url,
                podcast_name     = EXCLUDED.podcast_name,
                title            = EXCLUDED.title,
                audio_url        = EXCLUDED.audio_url,
                duration_seconds = EXCLUDED.duration_seconds,
                published_date   = EXCLUDED.published_date,
                show_notes       = EXCLUDED.show_notes,
                transcript       = EXCLUDED.transcript,
                summary          = EXCLUDED.summary,
                status           = EXCLUDED.status,
                error_message    = EXCLUDED.error_message,
                rss_item_id      = EXCLUDED.rss_item_id,
                updated_at       = EXCLUDED.updated_at
        """
    }
}
