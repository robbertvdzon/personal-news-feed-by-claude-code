package com.vdzon.newsfeedbackend.podcast.infrastructure

import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.TtsProvider
import com.vdzon.newsfeedbackend.storage.JdbcJsonb
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp

/**
 * Repository voor podcasts. Metadata én de MP3-bytes staan in Postgres
 * (`audio_bytes BYTEA`). Voorheen lag de MP3 op de OpenShift PVC onder
 * `${app.data-dir}/users/<u>/audio/`, maar daar gaf
 * `Files.createDirectories(/data/users)` regelmatig een
 * AccessDeniedException voor de pod-user.
 */
@Component
class PodcastRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val json: JdbcJsonb
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): Podcast = Podcast(
        id = rs.getString("id"),
        title = rs.getString("title"),
        periodDescription = rs.getString("period_description"),
        periodDays = rs.getInt("period_days"),
        durationMinutes = rs.getInt("duration_minutes"),
        status = PodcastStatus.valueOf(rs.getString("status")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        scriptText = rs.getString("script_text"),
        topics = json.readList(rs, "topics", String::class.java),
        durationSeconds = rs.getObject("duration_seconds") as? Int,
        customTopics = json.readList(rs, "custom_topics", String::class.java),
        ttsProvider = TtsProvider.valueOf(rs.getString("tts_provider")),
        podcastNumber = rs.getInt("podcast_number"),
        generationSeconds = rs.getObject("generation_seconds") as? Int,
        translatedFromEpisodeGuid = rs.getString("translated_from_episode_guid"),
        translatedFromFeedUrl = rs.getString("translated_from_feed_url"),
        translatedFromFeedName = rs.getString("translated_from_feed_name"),
        translatedFromEpisodeTitle = rs.getString("translated_from_episode_title"),
        translatedFromRssItemId = rs.getString("translated_from_rss_item_id"),
        errorMessage = rs.getString("error_message")
    )

    private fun params(username: String, p: Podcast) = MapSqlParameterSource()
        .addValue("username", username)
        .addValue("id", p.id)
        .addValue("title", p.title)
        .addValue("period_description", p.periodDescription)
        .addValue("period_days", p.periodDays)
        .addValue("duration_minutes", p.durationMinutes)
        .addValue("status", p.status.name)
        .addValue("created_at", Timestamp.from(p.createdAt))
        .addValue("script_text", p.scriptText)
        .addValue("topics", json.toJsonb(p.topics))
        .addValue("duration_seconds", p.durationSeconds)
        .addValue("custom_topics", json.toJsonb(p.customTopics))
        .addValue("tts_provider", p.ttsProvider.name)
        .addValue("podcast_number", p.podcastNumber)
        .addValue("generation_seconds", p.generationSeconds)
        .addValue("translated_from_episode_guid", p.translatedFromEpisodeGuid)
        .addValue("translated_from_feed_url", p.translatedFromFeedUrl)
        .addValue("translated_from_feed_name", p.translatedFromFeedName)
        .addValue("translated_from_episode_title", p.translatedFromEpisodeTitle)
        .addValue("translated_from_rss_item_id", p.translatedFromRssItemId)
        .addValue("error_message", p.errorMessage)

    fun load(username: String): MutableList<Podcast> =
        jdbc.query(
            "SELECT id, title, period_description, period_days, duration_minutes, status, " +
                "created_at, script_text, topics, duration_seconds, custom_topics, tts_provider, " +
                "podcast_number, generation_seconds, translated_from_episode_guid, " +
                "translated_from_feed_url, translated_from_feed_name, translated_from_episode_title, " +
                "translated_from_rss_item_id, error_message " +
                "FROM podcasts WHERE username = :u ORDER BY created_at DESC",
            MapSqlParameterSource("u", username),
            ::map
        ).toMutableList()

    /**
     * KAN-63: idempotency-lookup voor de translate-knop. Returnt de
     * bestaande vertaling voor [episodeGuid] (ongeacht status), of `null`
     * als er nog geen vertaling bestaat. Maakt gebruik van de partial
     * index `podcasts_translated_from_idx`.
     */
    fun findByTranslatedFromEpisodeGuid(username: String, episodeGuid: String): Podcast? =
        jdbc.query(
            "SELECT id, title, period_description, period_days, duration_minutes, status, " +
                "created_at, script_text, topics, duration_seconds, custom_topics, tts_provider, " +
                "podcast_number, generation_seconds, translated_from_episode_guid, " +
                "translated_from_feed_url, translated_from_feed_name, translated_from_episode_title, " +
                "translated_from_rss_item_id, error_message " +
                "FROM podcasts WHERE username = :u AND translated_from_episode_guid = :g " +
                "ORDER BY created_at DESC LIMIT 1",
            MapSqlParameterSource().addValue("u", username).addValue("g", episodeGuid),
            ::map
        ).firstOrNull()

    fun save(username: String, all: List<Podcast>) {
        jdbc.update("DELETE FROM podcasts WHERE username = :u", MapSqlParameterSource("u", username))
        all.forEach { upsert(username, it) }
    }

    fun upsert(username: String, podcast: Podcast): Podcast {
        jdbc.update(UPSERT_SQL, params(username, podcast))
        return podcast
    }

    fun delete(username: String, id: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM podcasts WHERE username = :u AND id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", id)
        )
        return n > 0
    }

    fun saveAudio(username: String, id: String, bytes: ByteArray) {
        jdbc.update(
            "UPDATE podcasts SET audio_bytes = :b WHERE username = :u AND id = :id",
            MapSqlParameterSource()
                .addValue("u", username)
                .addValue("id", id)
                .addValue("b", bytes)
        )
    }

    fun loadAudio(username: String, id: String): ByteArray? =
        jdbc.query(
            "SELECT audio_bytes FROM podcasts WHERE username = :u AND id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", id)
        ) { rs, _ -> rs.getBytes("audio_bytes") }.firstOrNull()

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO podcasts (
                username, id, title, period_description, period_days,
                duration_minutes, status, created_at, script_text, topics,
                duration_seconds, custom_topics, tts_provider,
                podcast_number, generation_seconds,
                translated_from_episode_guid, translated_from_feed_url,
                translated_from_feed_name, translated_from_episode_title,
                translated_from_rss_item_id, error_message
            ) VALUES (
                :username, :id, :title, :period_description, :period_days,
                :duration_minutes, :status, :created_at, :script_text, :topics,
                :duration_seconds, :custom_topics, :tts_provider,
                :podcast_number, :generation_seconds,
                :translated_from_episode_guid, :translated_from_feed_url,
                :translated_from_feed_name, :translated_from_episode_title,
                :translated_from_rss_item_id, :error_message
            )
            ON CONFLICT (username, id) DO UPDATE SET
                title                         = EXCLUDED.title,
                period_description            = EXCLUDED.period_description,
                period_days                   = EXCLUDED.period_days,
                duration_minutes              = EXCLUDED.duration_minutes,
                status                        = EXCLUDED.status,
                created_at                    = EXCLUDED.created_at,
                script_text                   = EXCLUDED.script_text,
                topics                        = EXCLUDED.topics,
                duration_seconds              = EXCLUDED.duration_seconds,
                custom_topics                 = EXCLUDED.custom_topics,
                tts_provider                  = EXCLUDED.tts_provider,
                podcast_number                = EXCLUDED.podcast_number,
                generation_seconds            = EXCLUDED.generation_seconds,
                translated_from_episode_guid  = EXCLUDED.translated_from_episode_guid,
                translated_from_feed_url      = EXCLUDED.translated_from_feed_url,
                translated_from_feed_name     = EXCLUDED.translated_from_feed_name,
                translated_from_episode_title = EXCLUDED.translated_from_episode_title,
                translated_from_rss_item_id   = EXCLUDED.translated_from_rss_item_id,
                error_message                 = EXCLUDED.error_message
        """
    }
}
