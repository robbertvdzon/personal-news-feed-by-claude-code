package com.vdzon.newsfeedbackend.rss.infrastructure

import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.storage.JdbcJsonb
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "postgres")
class PostgresRssItemRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val json: JdbcJsonb
) : RssItemRepository {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): RssItem = RssItem(
        id = rs.getString("id"),
        title = rs.getString("title"),
        summary = rs.getString("summary"),
        url = rs.getString("url"),
        category = rs.getString("category"),
        feedUrl = rs.getString("feed_url"),
        source = rs.getString("source"),
        snippet = rs.getString("snippet"),
        publishedDate = rs.getString("published_date"),
        timestamp = rs.getTimestamp("timestamp").toInstant(),
        processedAt = rs.getTimestamp("processed_at")?.toInstant(),
        inFeed = rs.getBoolean("in_feed"),
        feedReason = rs.getString("feed_reason"),
        isRead = rs.getBoolean("is_read"),
        starred = rs.getBoolean("starred"),
        liked = rs.getObject("liked") as? Boolean,
        topics = json.readList(rs, "topics", String::class.java),
        feedItemId = rs.getString("feed_item_id")
    )

    private fun params(username: String, item: RssItem) = MapSqlParameterSource()
        .addValue("username", username)
        .addValue("id", item.id)
        .addValue("title", item.title)
        .addValue("summary", item.summary)
        .addValue("url", item.url)
        .addValue("category", item.category)
        .addValue("feed_url", item.feedUrl)
        .addValue("source", item.source)
        .addValue("snippet", item.snippet)
        .addValue("published_date", item.publishedDate)
        .addValue("timestamp", Timestamp.from(item.timestamp))
        .addValue("processed_at", item.processedAt?.let { Timestamp.from(it) })
        .addValue("in_feed", item.inFeed)
        .addValue("feed_reason", item.feedReason)
        .addValue("is_read", item.isRead)
        .addValue("starred", item.starred)
        .addValue("liked", item.liked)
        .addValue("topics", json.toJsonb(item.topics))
        .addValue("feed_item_id", item.feedItemId)

    override fun load(username: String): MutableList<RssItem> =
        jdbc.query(
            "SELECT * FROM rss_items WHERE username = :u ORDER BY timestamp DESC",
            MapSqlParameterSource("u", username),
            ::map
        ).toMutableList()

    override fun save(username: String, items: List<RssItem>) {
        jdbc.update(
            "DELETE FROM rss_items WHERE username = :u",
            MapSqlParameterSource("u", username)
        )
        items.forEach { upsert(username, it) }
    }

    override fun upsert(username: String, item: RssItem): RssItem {
        jdbc.update(UPSERT_SQL, params(username, item))
        return item
    }

    override fun upsertAll(username: String, batch: List<RssItem>) {
        // Batch update via per-item insert; voor ~tientallen rijen prima.
        // Voor hogere volumes kan dit naar jdbc.batchUpdate omgezet worden.
        batch.forEach { upsert(username, it) }
    }

    override fun delete(username: String, id: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM rss_items WHERE username = :u AND id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", id)
        )
        return n > 0
    }

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO rss_items (
                username, id, title, summary, url, category, feed_url, source,
                snippet, published_date, timestamp, processed_at, in_feed,
                feed_reason, is_read, starred, liked, topics, feed_item_id
            ) VALUES (
                :username, :id, :title, :summary, :url, :category, :feed_url, :source,
                :snippet, :published_date, :timestamp, :processed_at, :in_feed,
                :feed_reason, :is_read, :starred, :liked, :topics, :feed_item_id
            )
            ON CONFLICT (username, id) DO UPDATE SET
                title          = EXCLUDED.title,
                summary        = EXCLUDED.summary,
                url            = EXCLUDED.url,
                category       = EXCLUDED.category,
                feed_url       = EXCLUDED.feed_url,
                source         = EXCLUDED.source,
                snippet        = EXCLUDED.snippet,
                published_date = EXCLUDED.published_date,
                timestamp      = EXCLUDED.timestamp,
                processed_at   = EXCLUDED.processed_at,
                in_feed        = EXCLUDED.in_feed,
                feed_reason    = EXCLUDED.feed_reason,
                is_read        = EXCLUDED.is_read,
                starred        = EXCLUDED.starred,
                liked          = EXCLUDED.liked,
                topics         = EXCLUDED.topics,
                feed_item_id   = EXCLUDED.feed_item_id
        """
    }
}
