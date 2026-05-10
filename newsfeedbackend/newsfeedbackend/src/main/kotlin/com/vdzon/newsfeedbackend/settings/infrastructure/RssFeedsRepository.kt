package com.vdzon.newsfeedbackend.settings.infrastructure

import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

interface RssFeedsRepository {
    fun load(username: String): RssFeedsSettings
    fun save(username: String, settings: RssFeedsSettings)
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonRssFeedsRepository(private val store: JsonStore) : RssFeedsRepository {
    private fun file(u: String) = store.userFile(u, "rss_feeds.json")

    override fun load(username: String): RssFeedsSettings =
        store.readJson(file(username), RssFeedsSettings::class.java, RssFeedsSettings())

    override fun save(username: String, settings: RssFeedsSettings) {
        store.writeJson(file(username), settings)
    }
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "postgres")
class PostgresRssFeedsRepository(
    private val jdbc: NamedParameterJdbcTemplate
) : RssFeedsRepository {

    override fun load(username: String): RssFeedsSettings {
        val urls = jdbc.queryForList(
            "SELECT url FROM rss_feeds WHERE username = :u ORDER BY sort_order, url",
            MapSqlParameterSource("u", username),
            String::class.java
        )
        return RssFeedsSettings(feeds = urls)
    }

    override fun save(username: String, settings: RssFeedsSettings) {
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
