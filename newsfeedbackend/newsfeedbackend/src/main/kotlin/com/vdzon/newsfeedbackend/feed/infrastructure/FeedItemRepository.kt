package com.vdzon.newsfeedbackend.feed.infrastructure

import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.storage.JdbcJsonb
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp

@Component
class FeedItemRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val json: JdbcJsonb
) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): FeedItem = FeedItem(
        id = rs.getString("id"),
        title = rs.getString("title"),
        titleNl = rs.getString("title_nl"),
        summary = rs.getString("summary"),
        shortSummary = rs.getString("short_summary"),
        url = rs.getString("url"),
        category = rs.getString("category"),
        source = rs.getString("source"),
        sourceRssIds = json.readList(rs, "source_rss_ids", String::class.java),
        sourceUrls = json.readList(rs, "source_urls", String::class.java),
        topics = json.readList(rs, "topics", String::class.java),
        feedReason = rs.getString("feed_reason"),
        isRead = rs.getBoolean("is_read"),
        starred = rs.getBoolean("starred"),
        liked = rs.getObject("liked") as? Boolean,
        createdAt = rs.getTimestamp("created_at").toInstant(),
        publishedDate = rs.getString("published_date"),
        isSummary = rs.getBoolean("is_summary")
    )

    private fun params(username: String, item: FeedItem) = MapSqlParameterSource()
        .addValue("username", username)
        .addValue("id", item.id)
        .addValue("title", item.title)
        .addValue("title_nl", item.titleNl)
        .addValue("summary", item.summary)
        .addValue("short_summary", item.shortSummary)
        .addValue("url", item.url)
        .addValue("category", item.category)
        .addValue("source", item.source)
        .addValue("source_rss_ids", json.toJsonb(item.sourceRssIds))
        .addValue("source_urls", json.toJsonb(item.sourceUrls))
        .addValue("topics", json.toJsonb(item.topics))
        .addValue("feed_reason", item.feedReason)
        .addValue("is_read", item.isRead)
        .addValue("starred", item.starred)
        .addValue("liked", item.liked)
        .addValue("created_at", Timestamp.from(item.createdAt))
        .addValue("published_date", item.publishedDate)
        .addValue("is_summary", item.isSummary)

    fun load(username: String): MutableList<FeedItem> =
        jdbc.query(
            "SELECT * FROM feed_items WHERE username = :u ORDER BY created_at DESC",
            MapSqlParameterSource("u", username),
            ::map
        ).toMutableList()

    fun save(username: String, items: List<FeedItem>) {
        jdbc.update("DELETE FROM feed_items WHERE username = :u", MapSqlParameterSource("u", username))
        items.forEach { upsert(username, it) }
    }

    fun upsert(username: String, item: FeedItem): FeedItem {
        jdbc.update(UPSERT_SQL, params(username, item))
        return item
    }

    fun delete(username: String, id: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM feed_items WHERE username = :u AND id = :id",
            MapSqlParameterSource().addValue("u", username).addValue("id", id)
        )
        return n > 0
    }

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO feed_items (
                username, id, title, title_nl, summary, short_summary, url,
                category, source, source_rss_ids, source_urls, topics,
                feed_reason, is_read, starred, liked, created_at,
                published_date, is_summary
            ) VALUES (
                :username, :id, :title, :title_nl, :summary, :short_summary, :url,
                :category, :source, :source_rss_ids, :source_urls, :topics,
                :feed_reason, :is_read, :starred, :liked, :created_at,
                :published_date, :is_summary
            )
            ON CONFLICT (username, id) DO UPDATE SET
                title           = EXCLUDED.title,
                title_nl        = EXCLUDED.title_nl,
                summary         = EXCLUDED.summary,
                short_summary   = EXCLUDED.short_summary,
                url             = EXCLUDED.url,
                category        = EXCLUDED.category,
                source          = EXCLUDED.source,
                source_rss_ids  = EXCLUDED.source_rss_ids,
                source_urls     = EXCLUDED.source_urls,
                topics          = EXCLUDED.topics,
                feed_reason     = EXCLUDED.feed_reason,
                is_read         = EXCLUDED.is_read,
                starred         = EXCLUDED.starred,
                liked           = EXCLUDED.liked,
                created_at      = EXCLUDED.created_at,
                published_date  = EXCLUDED.published_date,
                is_summary      = EXCLUDED.is_summary
        """
    }
}
