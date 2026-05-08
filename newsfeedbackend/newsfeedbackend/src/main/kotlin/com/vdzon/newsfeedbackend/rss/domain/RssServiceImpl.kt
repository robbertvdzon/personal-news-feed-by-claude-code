package com.vdzon.newsfeedbackend.rss.domain

import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.RssService
import com.vdzon.newsfeedbackend.rss.infrastructure.RssItemRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

data class RssRefreshRequested(val username: String)
data class RssReselectRequested(val username: String)

@Service
class RssServiceImpl(
    private val repo: RssItemRepository,
    private val events: ApplicationEventPublisher
) : RssService {

    override fun list(username: String): List<RssItem> =
        repo.load(username).sortedByDescending { it.timestamp }

    override fun get(username: String, id: String): RssItem? =
        repo.load(username).find { it.id == id }

    override fun delete(username: String, id: String): Boolean = repo.delete(username, id)

    override fun setRead(username: String, id: String, read: Boolean): Boolean = mutate(username, id) {
        it.copy(isRead = read)
    }

    override fun markAllRead(username: String): Int {
        val items = repo.load(username)
        var changed = 0
        for (i in items.indices) {
            if (!items[i].isRead) {
                items[i] = items[i].copy(isRead = true)
                changed++
            }
        }
        if (changed > 0) repo.save(username, items)
        return changed
    }

    override fun toggleStar(username: String, id: String): Boolean = mutate(username, id) {
        it.copy(starred = !it.starred)
    }

    override fun setFeedback(username: String, id: String, liked: Boolean?): Boolean = mutate(username, id) {
        it.copy(liked = liked)
    }

    override fun cleanup(
        username: String,
        olderThanDays: Int,
        keepStarred: Boolean,
        keepLiked: Boolean,
        keepUnread: Boolean
    ): Int {
        val cutoff = Instant.now().minus(olderThanDays.toLong(), ChronoUnit.DAYS)
        val items = repo.load(username)
        val before = items.size
        items.removeAll { item ->
            if (item.timestamp.isAfter(cutoff)) return@removeAll false
            if (keepStarred && item.starred) return@removeAll false
            if (keepLiked && item.liked == true) return@removeAll false
            if (keepUnread && !item.isRead) return@removeAll false
            true
        }
        val removed = before - items.size
        if (removed > 0) repo.save(username, items)
        return removed
    }

    override fun triggerRefresh(username: String) {
        events.publishEvent(RssRefreshRequested(username))
    }

    override fun triggerReselect(username: String) {
        events.publishEvent(RssReselectRequested(username))
    }

    private fun mutate(username: String, id: String, fn: (RssItem) -> RssItem): Boolean {
        val items = repo.load(username)
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        items[idx] = fn(items[idx])
        repo.save(username, items)
        return true
    }
}
