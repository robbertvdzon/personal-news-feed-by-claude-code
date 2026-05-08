package com.vdzon.newsfeedbackend.feed.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class FeedItemRepository(private val store: JsonStore) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun file(username: String) = store.userFile(username, "feed_items.json")
    private fun lock(username: String) = locks.computeIfAbsent(username) { ReentrantLock() }

    fun load(username: String): MutableList<FeedItem> = lock(username).withLock {
        store.readJsonRef(file(username), object : TypeReference<MutableList<FeedItem>>() {}, mutableListOf())
    }

    fun save(username: String, items: List<FeedItem>) = lock(username).withLock {
        store.writeJson(file(username), items)
    }

    fun upsert(username: String, item: FeedItem): FeedItem = lock(username).withLock {
        val items = load(username)
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        store.writeJson(file(username), items)
        item
    }

    fun delete(username: String, id: String): Boolean = lock(username).withLock {
        val items = load(username)
        val removed = items.removeAll { it.id == id }
        if (removed) store.writeJson(file(username), items)
        removed
    }
}
