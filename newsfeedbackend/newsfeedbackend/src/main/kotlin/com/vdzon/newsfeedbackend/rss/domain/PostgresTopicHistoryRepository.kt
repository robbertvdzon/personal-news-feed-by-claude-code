package com.vdzon.newsfeedbackend.rss.domain

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Postgres-backend voor topic_history. De JSON-impl bewaart Instants als
 * String (Instant.toString()); we converteren naar TIMESTAMPTZ en
 * teruglezen we 'm als ISO-string voor compat met de bestaande TopicEntry.
 */
@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "postgres")
class PostgresTopicHistoryRepository(
    private val jdbc: NamedParameterJdbcTemplate
) : TopicHistoryRepository {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): TopicEntry = TopicEntry(
        topic = rs.getString("topic"),
        firstSeen = rs.getTimestamp("first_seen").toInstant().toString(),
        lastSeenNews = rs.getTimestamp("last_seen_news")?.toInstant()?.toString(),
        lastSeenPodcast = rs.getTimestamp("last_seen_podcast")?.toInstant()?.toString(),
        newsCount = rs.getInt("news_count"),
        podcastMentionCount = rs.getInt("podcast_mention_count"),
        podcastDeepCount = rs.getInt("podcast_deep_count"),
        likedCount = rs.getInt("liked_count"),
        starredCount = rs.getInt("starred_count")
    )

    private fun parseTs(s: String?): Timestamp? =
        if (s.isNullOrBlank()) null else try { Timestamp.from(Instant.parse(s)) } catch (_: Exception) { null }

    override fun load(username: String): MutableList<TopicEntry> =
        jdbc.query(
            "SELECT * FROM topic_history WHERE username = :u ORDER BY topic",
            MapSqlParameterSource("u", username),
            ::map
        ).toMutableList()

    override fun save(username: String, entries: List<TopicEntry>) {
        jdbc.update("DELETE FROM topic_history WHERE username = :u", MapSqlParameterSource("u", username))
        entries.forEach { e ->
            jdbc.update(
                """
                INSERT INTO topic_history (
                    username, topic, first_seen, last_seen_news, last_seen_podcast,
                    news_count, podcast_mention_count, podcast_deep_count,
                    liked_count, starred_count
                ) VALUES (
                    :u, :topic, :first_seen, :last_seen_news, :last_seen_podcast,
                    :news_count, :podcast_mention_count, :podcast_deep_count,
                    :liked_count, :starred_count
                )
                """,
                MapSqlParameterSource()
                    .addValue("u", username)
                    .addValue("topic", e.topic)
                    .addValue("first_seen", parseTs(e.firstSeen) ?: Timestamp.from(Instant.now()))
                    .addValue("last_seen_news", parseTs(e.lastSeenNews))
                    .addValue("last_seen_podcast", parseTs(e.lastSeenPodcast))
                    .addValue("news_count", e.newsCount)
                    .addValue("podcast_mention_count", e.podcastMentionCount)
                    .addValue("podcast_deep_count", e.podcastDeepCount)
                    .addValue("liked_count", e.likedCount)
                    .addValue("starred_count", e.starredCount)
            )
        }
    }

    override fun update(username: String, op: (MutableList<TopicEntry>) -> Unit) {
        // Locking: gebruik een tx + advisory-lock op de username? Voor
        // single-instance is een load+save voldoende; bij multi-instance
        // moet hier een SELECT FOR UPDATE of advisory-lock bij.
        val entries = load(username)
        op(entries)
        save(username, entries)
    }
}
