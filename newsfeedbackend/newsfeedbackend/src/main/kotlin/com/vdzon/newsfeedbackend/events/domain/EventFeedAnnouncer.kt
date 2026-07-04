package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.events.Event
import com.vdzon.newsfeedbackend.feed.FeedItem
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * Bouwt het Nederlandstalige aankondigings-[FeedItem] voor een nieuw
 * ontdekt event, zodat de gebruiker het in zijn feed ziet verschijnen.
 */
@Component
class EventFeedAnnouncer {

    fun announcementFeedItem(ev: Event): FeedItem {
        val whenStr = formatDutchDate(ev.startDate)
        val org = ev.organization?.takeIf { it.isNotBlank() }?.let { " van $it" } ?: ""
        val loc = ev.location.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""
        val short = "Op $whenStr is er het tech-event ${ev.name}$org$loc gepland."
        val long = buildString {
            append("Op $whenStr is er ${ev.name}$org$loc gepland.")
            if (ev.description.isNotBlank()) {
                append(" Met deze onderwerpen: ${ev.description}")
            }
            append("\n\nBekijk dit event in de Events-sectie voor de volledige beschrijving, datum, locatie en bronlinks.")
        }
        return FeedItem(
            id = UUID.randomUUID().toString(),
            title = ev.name,
            titleNl = ev.name,
            summary = long,
            shortSummary = short,
            url = ev.sourceLinks.firstOrNull(),
            category = ev.category,
            source = ev.organization ?: "",
            sourceUrls = ev.sourceLinks,
            topics = listOf(ev.name),
            feedReason = "Automatisch ontdekt tech-event — zie de Events-sectie",
            publishedDate = ev.startDate,
            createdAt = Instant.now(),
            mediaType = "ARTICLE"
        )
    }

    private fun formatDutchDate(date: String?): String {
        if (date == null) return "binnenkort"
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val month = d.month.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("nl"))
        return "${d.dayOfMonth} $month ${d.year}"
    }
}
