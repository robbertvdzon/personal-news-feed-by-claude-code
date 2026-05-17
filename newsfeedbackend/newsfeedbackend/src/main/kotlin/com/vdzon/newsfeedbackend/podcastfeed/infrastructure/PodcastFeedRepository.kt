package com.vdzon.newsfeedbackend.podcastfeed.infrastructure

import com.vdzon.newsfeedbackend.podcastfeed.PodcastFeed
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

@Component
class PodcastFeedRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): PodcastFeed = PodcastFeed(
        url = rs.getString("url"),
        transcribeEnabled = rs.getBoolean("transcribe_enabled")
    )

    fun load(username: String): List<PodcastFeed> = jdbc.query(
        "SELECT url, transcribe_enabled FROM podcast_feeds WHERE username = :u ORDER BY sort_order, url",
        MapSqlParameterSource("u", username),
        ::map
    )

    /**
     * Slaat de hele set bronnen voor een user op. Verwijdert bronnen die
     * niet meer in de lijst zitten (CASCADE pakt bijbehorende episodes
     * mee), upsert de rest. Returnt de set die nu in de DB staat.
     */
    fun save(username: String, feeds: List<PodcastFeed>): List<PodcastFeed> {
        val urls = feeds.map { it.url }
        if (urls.isEmpty()) {
            jdbc.update("DELETE FROM podcast_feeds WHERE username = :u", MapSqlParameterSource("u", username))
        } else {
            jdbc.update(
                "DELETE FROM podcast_feeds WHERE username = :u AND url NOT IN (:urls)",
                MapSqlParameterSource().addValue("u", username).addValue("urls", urls)
            )
            feeds.forEachIndexed { idx, f ->
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
                        .addValue("url", f.url)
                        .addValue("te", f.transcribeEnabled)
                        .addValue("sort", idx)
                )
            }
        }
        return load(username)
    }

    fun delete(username: String, url: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM podcast_feeds WHERE username = :u AND url = :url",
            MapSqlParameterSource().addValue("u", username).addValue("url", url)
        )
        return n > 0
    }

    fun find(username: String, url: String): PodcastFeed? = jdbc.query(
        "SELECT url, transcribe_enabled FROM podcast_feeds WHERE username = :u AND url = :url",
        MapSqlParameterSource().addValue("u", username).addValue("url", url),
        ::map
    ).firstOrNull()
}
