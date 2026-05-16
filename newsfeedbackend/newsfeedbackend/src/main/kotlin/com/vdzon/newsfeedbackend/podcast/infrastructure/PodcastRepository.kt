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
 * Repository voor podcasts. Metadata én MP3-audio staan in Postgres
 * (`audio_data BYTEA`). Audio wordt lazy gelezen via [loadAudio] zodat
 * de list-/get-queries geen meerdere MB per rij hoeven op te halen.
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
        generationSeconds = rs.getObject("generation_seconds") as? Int
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

    fun load(username: String): MutableList<Podcast> =
        jdbc.query(
            // audio_data expliciet niet selecteren — anders sleep je per
            // podcast-row enkele MB MP3 mee voor list/get-queries.
            """
            SELECT username, id, title, period_description, period_days,
                   duration_minutes, status, created_at, script_text,
                   topics, duration_seconds, custom_topics, tts_provider,
                   podcast_number, generation_seconds
            FROM podcasts WHERE username = :u ORDER BY created_at DESC
            """,
            MapSqlParameterSource("u", username),
            ::map
        ).toMutableList()

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

    fun loadAudio(username: String, id: String): ByteArray? {
        return jdbc.query(
            "SELECT audio_data FROM podcasts WHERE username = :u AND id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", id)
        ) { rs, _ -> rs.getBytes("audio_data") }.firstOrNull()
    }

    fun saveAudio(username: String, id: String, bytes: ByteArray): Int {
        return jdbc.update(
            "UPDATE podcasts SET audio_data = :bytes WHERE username = :u AND id = :id",
            MapSqlParameterSource()
                .addValue("u", username)
                .addValue("id", id)
                .addValue("bytes", bytes)
        )
    }

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO podcasts (
                username, id, title, period_description, period_days,
                duration_minutes, status, created_at, script_text, topics,
                duration_seconds, custom_topics, tts_provider,
                podcast_number, generation_seconds
            ) VALUES (
                :username, :id, :title, :period_description, :period_days,
                :duration_minutes, :status, :created_at, :script_text, :topics,
                :duration_seconds, :custom_topics, :tts_provider,
                :podcast_number, :generation_seconds
            )
            ON CONFLICT (username, id) DO UPDATE SET
                title              = EXCLUDED.title,
                period_description = EXCLUDED.period_description,
                period_days        = EXCLUDED.period_days,
                duration_minutes   = EXCLUDED.duration_minutes,
                status             = EXCLUDED.status,
                created_at         = EXCLUDED.created_at,
                script_text        = EXCLUDED.script_text,
                topics             = EXCLUDED.topics,
                duration_seconds   = EXCLUDED.duration_seconds,
                custom_topics      = EXCLUDED.custom_topics,
                tts_provider       = EXCLUDED.tts_provider,
                podcast_number     = EXCLUDED.podcast_number,
                generation_seconds = EXCLUDED.generation_seconds
        """
    }
}
