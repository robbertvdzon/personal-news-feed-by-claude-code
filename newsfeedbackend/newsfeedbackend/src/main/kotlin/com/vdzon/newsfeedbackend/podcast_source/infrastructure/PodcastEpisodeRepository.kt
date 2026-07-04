package com.vdzon.newsfeedbackend.podcast_source.infrastructure

import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeLookup
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.storage.JdbcJsonb
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Component
class PodcastEpisodeRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val json: JdbcJsonb
) : PodcastEpisodeLookup {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): PodcastEpisode = PodcastEpisode(
        username = rs.getString("username"),
        guid = rs.getString("guid"),
        feedUrl = rs.getString("feed_url"),
        podcastName = rs.getString("podcast_name") ?: "",
        title = rs.getString("title") ?: "",
        audioUrl = rs.getString("audio_url") ?: "",
        durationSeconds = rs.getObject("duration_seconds") as? Int,
        publishedDate = rs.getString("published_date"),
        showNotes = rs.getString("show_notes") ?: "",
        transcript = rs.getString("transcript") ?: "",
        summary = rs.getString("summary") ?: "",
        status = runCatching {
            PodcastEpisodeStatus.valueOf(rs.getString("status") ?: "PENDING")
        }.getOrDefault(PodcastEpisodeStatus.PENDING),
        errorMessage = rs.getString("error_message"),
        rssItemId = rs.getString("rss_item_id"),
        retryCount = rs.getInt("retry_count"),
        nextAttemptAt = rs.getTimestamp("next_attempt_at")?.toInstant(),
        summarySource = rs.getString("summary_source") ?: "transcript",
        longSummary = rs.getString("long_summary"),
        keyTakeaways = json.readList(rs, "key_takeaways", String::class.java),
        feedPromotionAttemptedAt = rs.getTimestamp("feed_promotion_attempted_at")?.toInstant(),
        createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.now(),
        updatedAt = rs.getTimestamp("updated_at")?.toInstant() ?: Instant.now()
    )

    fun load(username: String): List<PodcastEpisode> =
        jdbc.query(
            "SELECT * FROM podcast_episodes WHERE username = :u ORDER BY created_at DESC",
            MapSqlParameterSource("u", username),
            ::map
        )

    override fun get(username: String, guid: String): PodcastEpisode? =
        jdbc.query(
            "SELECT * FROM podcast_episodes WHERE username = :u AND guid = :g",
            MapSqlParameterSource().addValue("u", username).addValue("g", guid),
            ::map
        ).firstOrNull()

    fun existingGuids(username: String, feedUrl: String): Set<String> =
        jdbc.queryForList(
            "SELECT guid FROM podcast_episodes WHERE username = :u AND feed_url = :f",
            MapSqlParameterSource().addValue("u", username).addValue("f", feedUrl),
            String::class.java
        ).toSet()

    fun countForFeed(username: String, feedUrl: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM podcast_episodes WHERE username = :u AND feed_url = :f",
            MapSqlParameterSource().addValue("u", username).addValue("f", feedUrl),
            Int::class.java
        ) ?: 0

    fun upsert(ep: PodcastEpisode): PodcastEpisode {
        val withTs = ep.copy(updatedAt = Instant.now())
        jdbc.update(UPSERT_SQL, params(withTs))
        return withTs
    }

    fun deleteForFeed(username: String, feedUrl: String): Int =
        jdbc.update(
            "DELETE FROM podcast_episodes WHERE username = :u AND feed_url = :f",
            MapSqlParameterSource().addValue("u", username).addValue("f", feedUrl)
        )

    fun resetFailedWithOomError(): Int =
        jdbc.update(
            """UPDATE podcast_episodes
               SET status = :status, error_message = NULL, updated_at = NOW()
               WHERE status = 'FAILED'
               AND (error_message LIKE '%heap space%' OR error_message = 'Audio-download faalde')""",
            MapSqlParameterSource("status", "PENDING")
        )

    /**
     * KAN-60: pakt globaal de oudste aflevering die klaar is voor een
     * (nieuwe) Whisper-poging — status=NEEDS_TRANSCRIPT en niet meer in
     * de backoff-wachtkamer. FIFO over alle gebruikers heen; per tick
     * wordt er max één opgepakt zodat we Whisper niet weer met een burst
     * overstelpen (AC #3).
     */
    fun findOneReadyForTranscript(now: Instant): PodcastEpisode? =
        jdbc.query(
            """SELECT * FROM podcast_episodes
               WHERE status = 'NEEDS_TRANSCRIPT'
                 AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
               ORDER BY created_at ASC
               LIMIT 1""",
            MapSqlParameterSource("now", Timestamp.from(now)),
            ::map
        ).firstOrNull()

    /**
     * KAN-60: pakt afleveringen die langer dan [olderThan] vastzitten op
     * NEEDS_TRANSCRIPT en waarvoor we de show-notes-feed-promotie nog
     * niet hebben getriggerd. Deze worden alsnog gepromoot op basis van
     * de show-notes-samenvatting (AC #6).
     *
     * V8/follow-up: filteren op `feed_promotion_attempted_at IS NULL`
     * i.p.v. `ri.feed_item_id IS NULL`. Een AI-afwijzing in
     * `promoteSingleItem` laat `feed_item_id` op NULL staan, dus die
     * conditie matchte iedere tick opnieuw → Claude-call-loop.
     */
    fun findShowNotesExpiredForPromotion(now: Instant, olderThan: java.time.Duration): List<PodcastEpisode> {
        val threshold = now.minus(olderThan)
        return jdbc.query(
            """SELECT pe.* FROM podcast_episodes pe
               WHERE pe.status = 'NEEDS_TRANSCRIPT'
                 AND pe.created_at <= :threshold
                 AND pe.feed_promotion_attempted_at IS NULL
                 AND pe.rss_item_id IS NOT NULL
               ORDER BY pe.created_at ASC""",
            MapSqlParameterSource("threshold", Timestamp.from(threshold)),
            ::map
        )
    }

    /**
     * KAN-62: alle DONE-afleveringen die nog geen long_summary hebben.
     * Wordt door [com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastBackfillRunner]
     * gebruikt om bestaande KAN-60-rijen retroactief te verrijken met
     * de uitgebreidere Claude-output (story AC #7). Alleen transcript-
     * based items — show-notes-rijen hebben geen transcript om uit te
     * vertrekken (refiner-aanname).
     */
    fun findDoneNeedingLongSummary(): List<PodcastEpisode> =
        jdbc.query(
            """SELECT * FROM podcast_episodes
               WHERE status = 'DONE'
                 AND summary_source = 'transcript'
                 AND long_summary IS NULL
               ORDER BY created_at ASC""",
            MapSqlParameterSource(),
            ::map
        )

    /**
     * Zet de marker dat de show-notes-timeout-promotie voor [guid]
     * getriggerd is, zodat [findShowNotesExpiredForPromotion] 'm niet
     * opnieuw oppakt. Aparte UPDATE (niet via [upsert]) zodat we niet
     * per ongeluk andere velden overschrijven met een verouderde in-
     * memory kopie van de worker.
     */
    fun markFeedPromotionAttempted(username: String, guid: String, at: Instant): Int =
        jdbc.update(
            """UPDATE podcast_episodes
               SET feed_promotion_attempted_at = :at, updated_at = NOW()
               WHERE username = :u AND guid = :g""",
            MapSqlParameterSource()
                .addValue("u", username)
                .addValue("g", guid)
                .addValue("at", Timestamp.from(at))
        )

    private fun params(ep: PodcastEpisode) = MapSqlParameterSource()
        .addValue("username", ep.username)
        .addValue("guid", ep.guid)
        .addValue("feed_url", ep.feedUrl)
        .addValue("podcast_name", ep.podcastName)
        .addValue("title", ep.title)
        .addValue("audio_url", ep.audioUrl)
        .addValue("duration_seconds", ep.durationSeconds)
        .addValue("published_date", ep.publishedDate)
        .addValue("show_notes", ep.showNotes)
        .addValue("transcript", ep.transcript)
        .addValue("summary", ep.summary)
        .addValue("status", ep.status.name)
        .addValue("error_message", ep.errorMessage)
        .addValue("rss_item_id", ep.rssItemId)
        .addValue("retry_count", ep.retryCount)
        .addValue("next_attempt_at", ep.nextAttemptAt?.let { Timestamp.from(it) })
        .addValue("summary_source", ep.summarySource)
        .addValue("long_summary", ep.longSummary)
        .addValue("key_takeaways", json.toJsonb(ep.keyTakeaways))
        .addValue("feed_promotion_attempted_at", ep.feedPromotionAttemptedAt?.let { Timestamp.from(it) })
        .addValue("created_at", Timestamp.from(ep.createdAt))
        .addValue("updated_at", Timestamp.from(ep.updatedAt))

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO podcast_episodes (
                username, guid, feed_url, podcast_name, title, audio_url,
                duration_seconds, published_date, show_notes, transcript,
                summary, status, error_message, rss_item_id, retry_count,
                next_attempt_at, summary_source, long_summary, key_takeaways,
                feed_promotion_attempted_at, created_at, updated_at
            ) VALUES (
                :username, :guid, :feed_url, :podcast_name, :title, :audio_url,
                :duration_seconds, :published_date, :show_notes, :transcript,
                :summary, :status, :error_message, :rss_item_id, :retry_count,
                :next_attempt_at, :summary_source, :long_summary, :key_takeaways,
                :feed_promotion_attempted_at, :created_at, :updated_at
            )
            ON CONFLICT (username, guid) DO UPDATE SET
                feed_url                    = EXCLUDED.feed_url,
                podcast_name                = EXCLUDED.podcast_name,
                title                       = EXCLUDED.title,
                audio_url                   = EXCLUDED.audio_url,
                duration_seconds            = EXCLUDED.duration_seconds,
                published_date              = EXCLUDED.published_date,
                show_notes                  = EXCLUDED.show_notes,
                transcript                  = EXCLUDED.transcript,
                summary                     = EXCLUDED.summary,
                status                      = EXCLUDED.status,
                error_message               = EXCLUDED.error_message,
                rss_item_id                 = EXCLUDED.rss_item_id,
                retry_count                 = EXCLUDED.retry_count,
                next_attempt_at             = EXCLUDED.next_attempt_at,
                summary_source              = EXCLUDED.summary_source,
                long_summary                = EXCLUDED.long_summary,
                key_takeaways               = EXCLUDED.key_takeaways,
                feed_promotion_attempted_at = EXCLUDED.feed_promotion_attempted_at,
                updated_at                  = EXCLUDED.updated_at
        """
    }
    override fun findByRssItemId(username: String, rssItemId: String): PodcastEpisode? =
        load(username).firstOrNull { it.rssItemId == rssItemId }

}
