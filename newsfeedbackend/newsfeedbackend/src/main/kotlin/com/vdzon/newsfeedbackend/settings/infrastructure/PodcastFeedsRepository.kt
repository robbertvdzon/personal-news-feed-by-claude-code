package com.vdzon.newsfeedbackend.settings.infrastructure

import com.vdzon.newsfeedbackend.settings.PodcastFeed
import com.vdzon.newsfeedbackend.settings.PodcastFeedsSettings
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class PodcastFeedsRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    fun load(username: String): PodcastFeedsSettings {
        val rows = jdbc.query(
            "SELECT url, transcribe_enabled FROM podcast_feeds WHERE username = :u ORDER BY sort_order, url",
            MapSqlParameterSource("u", username)
        ) { rs, _ ->
            PodcastFeed(
                url = rs.getString("url"),
                transcribeEnabled = rs.getBoolean("transcribe_enabled")
            )
        }
        return PodcastFeedsSettings(feeds = rows)
    }

    fun save(username: String, settings: PodcastFeedsSettings) {
        jdbc.update(
            "DELETE FROM podcast_feeds WHERE username = :u",
            MapSqlParameterSource("u", username)
        )
        settings.feeds.forEachIndexed { idx, feed ->
            jdbc.update(
                """
                INSERT INTO podcast_feeds (username, url, transcribe_enabled, sort_order)
                VALUES (:u, :url, :te, :sort)
                ON CONFLICT (username, url) DO UPDATE SET
                    transcribe_enabled = EXCLUDED.transcribe_enabled,
                    sort_order = EXCLUDED.sort_order
                """,
                MapSqlParameterSource()
                    .addValue("u", username)
                    .addValue("url", feed.url)
                    .addValue("te", feed.transcribeEnabled)
                    .addValue("sort", idx)
            )
        }
    }
}
