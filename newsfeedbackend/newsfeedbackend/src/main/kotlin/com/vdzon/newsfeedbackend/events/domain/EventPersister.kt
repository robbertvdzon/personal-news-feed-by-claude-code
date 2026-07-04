package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.feed.FeedService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

/**
 * Dedup, denylist-filtering en opslag van discovered events, inclusief
 * het aankondigings-[com.vdzon.newsfeedbackend.feed.FeedItem] voor écht
 * nieuwe events (via [EventFeedAnnouncer]).
 */
@Component
class EventPersister(
    private val repo: EventRepository,
    private val feed: FeedService,
    private val dateEnricher: EventDateEnricher,
    private val announcer: EventFeedAnnouncer
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class PersistOutcome(
        val created: Int,
        val updated: Int,
        val rejectedNoDate: Int,
        val rejectedDenylisted: Int
    )

    /**
     * Verwerkt een verzameling discovered events: dedup, denylist,
     * date-recovery-via-Tavily, opslag + aankondigings-FeedItem voor
     * écht nieuwe events. De `existing`-lijst wordt in-place
     * geüpdatet zodat een tweede call binnen dezelfde run ook
     * direct dedup op de net-aangemaakte events.
     */
    fun persistDiscovered(
        username: String,
        discovered: List<Event>,
        existing: MutableList<Event>,
        denylist: HashSet<String>
    ): PersistOutcome {
        var created = 0
        var updated = 0
        var rejectedNoDate = 0
        var rejectedDenylisted = 0

        for (raw in discovered) {
            if (raw.id in denylist) {
                log.debug("[Events]   skip '{}' — staat op denylist", raw.id)
                rejectedDenylisted++
                continue
            }
            // KAN-68 AC: events zonder geldige start_date worden niet opgeslagen.
            // Probeer eerst één extra Tavily-lookup om de datum te
            // vinden voordat we 'm weggooien.
            val ev = dateEnricher.ensureStartDate(username, raw)
            if (!dateEnricher.hasValidStartDate(ev.startDate)) {
                log.info("[Events]   verwerp '{}' ({}) — geen geldige datum gevonden", ev.id, ev.name)
                rejectedNoDate++
                continue
            }
            if (!withinWindow(ev.startDate)) {
                log.debug("[Events]   skip '{}' — buiten window (start={})", ev.id, ev.startDate)
                continue
            }
            val prior = existing.find { it.id == ev.id }
            if (prior != null) {
                val merged = ev.copy(
                    feedItemId = prior.feedItemId,
                    createdAt = prior.createdAt,
                    updatedAt = Instant.now()
                )
                repo.upsert(username, merged)
                val idx = existing.indexOf(prior)
                if (idx >= 0) existing[idx] = merged
                updated++
            } else {
                val feedItem = announcer.announcementFeedItem(ev)
                feed.save(username, feedItem)
                val saved = ev.copy(feedItemId = feedItem.id)
                repo.upsert(username, saved)
                existing.add(saved)
                created++
                log.info("[Events]   NIEUW event '{}' ({}) + aankondiging in feed", ev.id, ev.name)
            }
        }
        return PersistOutcome(created, updated, rejectedNoDate, rejectedDenylisted)
    }

    /** Houd events die in de toekomst liggen of maximaal één jaar terug zijn. */
    private fun withinWindow(startDate: String?): Boolean {
        if (startDate == null) return false // KAN-68: null mag hier niet meer doorkomen
        val d = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return false
        return !d.isBefore(LocalDate.now().minusYears(1))
    }
}
