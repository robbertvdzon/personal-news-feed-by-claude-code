package com.vdzon.newsfeedbackend.storage

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.auth.domain.User
import com.vdzon.newsfeedbackend.auth.infrastructure.UserRepository
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.infrastructure.ExternalCallRepository
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.infrastructure.FeedItemRepository
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.infrastructure.PodcastRepository
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.infrastructure.RequestRepository
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.domain.TopicEntry
import com.vdzon.newsfeedbackend.rss.domain.TopicHistoryRepository
import com.vdzon.newsfeedbackend.rss.infrastructure.RssItemRepository
import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.settings.infrastructure.CategorySettingsRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.RssFeedsRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Eenmalige import van de bestaande JSON-data naar Postgres.
 *
 * Wordt alleen actief op `app.storage.backend=postgres`. Bij opstart:
 *   1. Check of de marker "json-to-postgres" al in `_migrations` staat.
 *      Zo ja → klaar, niets te doen.
 *   2. Lees `data/users.json`, voor elke user: INSERT in `users`, lees
 *      vervolgens de per-user files (feed_items, rss_items, news_requests,
 *      podcasts, topic_history, settings, rss_feeds) en delegeer naar de
 *      betreffende Postgres-repo.
 *   3. Lees `data/external_calls.jsonl` line-by-line en append elke regel.
 *   4. Schrijf de marker.
 *
 * De originele JSON-files blijven onaangeraakt — als de migratie faalt
 * kun je gewoon teruggaan naar `STORAGE_BACKEND=json` zonder dataverlies.
 *
 * `@Order(1)` zodat 'ie vóór de andere CommandLineRunners draait (zoals
 * StartupRunner die rss-requests reset).
 */
@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "postgres")
@Order(1)
class JsonToPostgresMigrator(
    private val store: JsonStore,
    private val mapper: ObjectMapper,
    private val jdbc: NamedParameterJdbcTemplate,
    private val users: UserRepository,
    private val feedItems: FeedItemRepository,
    private val rssItems: RssItemRepository,
    private val requests: RequestRepository,
    private val podcasts: PodcastRepository,
    private val topicHistory: TopicHistoryRepository,
    private val categorySettings: CategorySettingsRepository,
    private val rssFeeds: RssFeedsRepository,
    private val externalCalls: ExternalCallRepository
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val markerName = "json-to-postgres"

    override fun run(vararg args: String) {
        if (markerExists()) {
            log.info("[Migrator] marker '{}' aanwezig — JSON→Postgres migratie al gedraaid, skip", markerName)
            return
        }
        val root = store.root()
        if (!Files.exists(root)) {
            log.info("[Migrator] geen data-directory '{}' — niets te migreren, marker zet ik wel", root)
            writeMarker(0, 0)
            return
        }

        log.info("[Migrator] start JSON→Postgres migratie vanaf '{}'", root.toAbsolutePath())

        val usersLoaded = migrateUsers(root)
        log.info("[Migrator] {} users geïmporteerd", usersLoaded.size)

        var totalRows = 0
        usersLoaded.forEach { user ->
            val n = migrateUserData(root, user.username)
            log.info("[Migrator]   user '{}': {} rows over alle per-user tabellen", user.username, n)
            totalRows += n
        }

        val calls = migrateExternalCalls(root)
        log.info("[Migrator] {} external_calls geïmporteerd", calls)

        writeMarker(usersLoaded.size, totalRows + calls)
        log.info("[Migrator] klaar — marker '{}' weggeschreven", markerName)
    }

    // ----- per stap -------------------------------------------------------

    private fun migrateUsers(root: Path): List<User> {
        val file = root.resolve("users.json")
        if (!Files.exists(file)) return emptyList()
        val list = mapper.readValue(
            Files.readAllBytes(file),
            object : TypeReference<List<User>>() {}
        )
        list.forEach { users.add(it) }
        return list
    }

    private fun migrateUserData(root: Path, username: String): Int {
        val userDir = root.resolve("users").resolve(username)
        if (!Files.exists(userDir)) return 0
        var n = 0

        readListOrEmpty<FeedItem>(userDir.resolve("feed_items.json"))
            .also { n += it.size }
            .forEach { feedItems.upsert(username, it) }

        readListOrEmpty<RssItem>(userDir.resolve("rss_items.json"))
            .also { n += it.size }
            .forEach { rssItems.upsert(username, it) }

        readListOrEmpty<NewsRequest>(userDir.resolve("news_requests.json"))
            .also { n += it.size }
            .forEach { requests.upsert(username, it) }

        readListOrEmpty<Podcast>(userDir.resolve("podcasts.json"))
            .also { n += it.size }
            .forEach { podcasts.upsert(username, it) }

        val topics = readListOrEmpty<TopicEntry>(userDir.resolve("topic_history.json"))
        if (topics.isNotEmpty()) {
            topicHistory.save(username, topics)
            n += topics.size
        }

        val cats = readListOrEmpty<CategorySettings>(userDir.resolve("settings.json"))
        if (cats.isNotEmpty()) {
            categorySettings.save(username, cats)
            n += cats.size
        }

        val feedsFile = userDir.resolve("rss_feeds.json")
        if (Files.exists(feedsFile)) {
            val fs = mapper.readValue(Files.readAllBytes(feedsFile), RssFeedsSettings::class.java)
            if (fs.feeds.isNotEmpty()) {
                rssFeeds.save(username, fs)
                n += fs.feeds.size
            }
        }
        return n
    }

    private fun migrateExternalCalls(root: Path): Int {
        val file = root.resolve("external_calls.jsonl")
        if (!Files.exists(file)) return 0
        var n = 0
        Files.lines(file).use { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val call = mapper.readValue(line, ExternalCall::class.java)
                    externalCalls.append(call)
                    n++
                } catch (e: Exception) {
                    log.warn("[Migrator] external_calls regel overgeslagen: {}", e.message)
                }
            }
        }
        return n
    }

    // ----- marker helpers --------------------------------------------------

    private fun markerExists(): Boolean =
        (jdbc.queryForObject(
            "SELECT COUNT(*) FROM _migrations WHERE name = :n",
            MapSqlParameterSource("n", markerName),
            Long::class.java
        ) ?: 0L) > 0L

    private fun writeMarker(@Suppress("UNUSED_PARAMETER") userCount: Int, @Suppress("UNUSED_PARAMETER") rowCount: Int) {
        jdbc.update(
            "INSERT INTO _migrations (name) VALUES (:n) ON CONFLICT (name) DO NOTHING",
            MapSqlParameterSource("n", markerName)
        )
    }

    // ----- I/O helper ------------------------------------------------------

    private inline fun <reified T> readListOrEmpty(path: Path): List<T> {
        if (!Files.exists(path)) return emptyList()
        return mapper.readValue(Files.readAllBytes(path), object : TypeReference<List<T>>() {})
    }
}
