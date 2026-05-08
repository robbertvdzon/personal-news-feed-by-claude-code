package com.vdzon.newsfeedbackend.feed.domain

import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.feed.infrastructure.FeedItemRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class FeedServiceImpl(private val repo: FeedItemRepository) : FeedService {

    override fun list(username: String): List<FeedItem> =
        repo.load(username).sortedByDescending { it.createdAt }

    override fun get(username: String, id: String): FeedItem? =
        repo.load(username).find { it.id == id }

    override fun save(username: String, item: FeedItem): FeedItem = repo.upsert(username, item)

    override fun delete(username: String, id: String): Boolean = repo.delete(username, id)

    override fun setRead(username: String, id: String, read: Boolean): Boolean {
        val items = repo.load(username)
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        items[idx] = items[idx].copy(isRead = read)
        repo.save(username, items)
        return true
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

    override fun toggleStar(username: String, id: String): Boolean {
        val items = repo.load(username)
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        items[idx] = items[idx].copy(starred = !items[idx].starred)
        repo.save(username, items)
        return true
    }

    override fun setFeedback(username: String, id: String, liked: Boolean?): Boolean {
        val items = repo.load(username)
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        items[idx] = items[idx].copy(liked = liked)
        repo.save(username, items)
        return true
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
            if (item.createdAt.isAfter(cutoff)) return@removeAll false
            if (keepStarred && item.starred) return@removeAll false
            if (keepLiked && item.liked == true) return@removeAll false
            if (keepUnread && !item.isRead) return@removeAll false
            true
        }
        val removed = before - items.size
        if (removed > 0) repo.save(username, items)
        return removed
    }
}
