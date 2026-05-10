package com.vdzon.newsfeedbackend.settings.infrastructure

import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class RssFeedsRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    fun load(username: String): RssFeedsSettings {
        val urls = jdbc.queryForList(
            "SELECT url FROM rss_feeds WHERE username = :u ORDER BY sort_order, url",
            MapSqlParameterSource("u", username),
            String::class.java
        )
        return RssFeedsSettings(feeds = urls)
    }

    fun save(username: String, settings: RssFeedsSettings) {
        jdbc.update(
            "DELETE FROM rss_feeds WHERE username = :u",
            MapSqlParameterSource("u", username)
        )
        settings.feeds.forEachIndexed { idx, url ->
            jdbc.update(
                """
                INSERT INTO rss_feeds (username, url, sort_order)
                VALUES (:u, :url, :sort)
                ON CONFLICT (username, url) DO UPDATE SET sort_order = EXCLUDED.sort_order
                """,
                MapSqlParameterSource()
                    .addValue("u", username)
                    .addValue("url", url)
                    .addValue("sort", idx)
            )
        }
    }
}
