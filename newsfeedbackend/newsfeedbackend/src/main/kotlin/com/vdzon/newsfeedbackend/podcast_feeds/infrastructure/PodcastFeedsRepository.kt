package com.vdzon.newsfeedbackend.podcast_feeds.infrastructure

import com.vdzon.newsfeedbackend.podcast_feeds.PodcastFeedsSettings
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class PodcastFeedsRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    fun load(username: String): PodcastFeedsSettings {
        val urls = jdbc.query(
            "SELECT feed_url, transcribe_enabled FROM podcast_feeds WHERE username = :u ORDER BY sort_order, feed_url",
            MapSqlParameterSource("u", username)
        ) { rs, _ ->
            PodcastFeedsSettings.Feed(
                url = rs.getString("feed_url"),
                transcribeEnabled = rs.getBoolean("transcribe_enabled")
            )
        }
        return PodcastFeedsSettings(feeds = urls)
    }

    fun save(username: String, settings: PodcastFeedsSettings) {
        jdbc.update(
            "DELETE FROM podcast_feeds WHERE username = :u",
            MapSqlParameterSource("u", username)
        )
        settings.feeds.forEachIndexed { idx, feed ->
            jdbc.update(
                """
                INSERT INTO podcast_feeds (username, feed_url, transcribe_enabled, sort_order)
                VALUES (:u, :url, :enabled, :sort)
                ON CONFLICT (username, feed_url) DO UPDATE SET
                    transcribe_enabled = EXCLUDED.transcribe_enabled,
                    sort_order = EXCLUDED.sort_order
                """,
                MapSqlParameterSource()
                    .addValue("u", username)
                    .addValue("url", feed.url)
                    .addValue("enabled", feed.transcribeEnabled)
                    .addValue("sort", idx)
            )
        }
    }
}
