package com.vdzon.newsfeedbackend.podcast.infrastructure

import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.TtsProvider
import com.vdzon.newsfeedbackend.storage.JdbcJsonb
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.sql.Timestamp

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "postgres")
class PostgresPodcastRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val json: JdbcJsonb,
    // We hergebruiken JsonStore alleen voor het audio-pad — die files
    // staan nog gewoon op disk.
    private val store: JsonStore
) : PodcastRepository {

    override fun audioPath(username: String, podcastId: String): Path {
        val dir = store.userDir(username).resolve("audio")
        Files.createDirectories(dir)
        return dir.resolve("$podcastId.mp3")
    }

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
        audioPath = rs.getString("audio_path"),
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
        .addValue("audio_path", p.audioPath)
        .addValue("duration_seconds", p.durationSeconds)
        .addValue("custom_topics", json.toJsonb(p.customTopics))
        .addValue("tts_provider", p.ttsProvider.name)
        .addValue("podcast_number", p.podcastNumber)
        .addValue("generation_seconds", p.generationSeconds)

    override fun load(username: String): MutableList<Podcast> =
        jdbc.query(
            "SELECT * FROM podcasts WHERE username = :u ORDER BY created_at DESC",
            MapSqlParameterSource("u", username),
            ::map
        ).toMutableList()

    override fun save(username: String, all: List<Podcast>) {
        jdbc.update("DELETE FROM podcasts WHERE username = :u", MapSqlParameterSource("u", username))
        all.forEach { upsert(username, it) }
    }

    override fun upsert(username: String, podcast: Podcast): Podcast {
        jdbc.update(UPSERT_SQL, params(username, podcast))
        return podcast
    }

    override fun delete(username: String, id: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM podcasts WHERE username = :u AND id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", id)
        )
        if (n > 0) Files.deleteIfExists(audioPath(username, id))
        return n > 0
    }

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO podcasts (
                username, id, title, period_description, period_days,
                duration_minutes, status, created_at, script_text, topics,
                audio_path, duration_seconds, custom_topics, tts_provider,
                podcast_number, generation_seconds
            ) VALUES (
                :username, :id, :title, :period_description, :period_days,
                :duration_minutes, :status, :created_at, :script_text, :topics,
                :audio_path, :duration_seconds, :custom_topics, :tts_provider,
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
                audio_path         = EXCLUDED.audio_path,
                duration_seconds   = EXCLUDED.duration_seconds,
                custom_topics      = EXCLUDED.custom_topics,
                tts_provider       = EXCLUDED.tts_provider,
                podcast_number     = EXCLUDED.podcast_number,
                generation_seconds = EXCLUDED.generation_seconds
        """
    }
}
