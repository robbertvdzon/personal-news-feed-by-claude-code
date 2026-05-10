package com.vdzon.newsfeedbackend.feed.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface FeedItemRepository {
    fun load(username: String): MutableList<FeedItem>
    fun save(username: String, items: List<FeedItem>)
    fun upsert(username: String, item: FeedItem): FeedItem
    fun delete(username: String, id: String): Boolean
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonFeedItemRepository(private val store: JsonStore) : FeedItemRepository {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun file(username: String) = store.userFile(username, "feed_items.json")
    private fun lock(username: String) = locks.computeIfAbsent(username) { ReentrantLock() }

    override fun load(username: String): MutableList<FeedItem> = lock(username).withLock {
        store.readJsonRef(file(username), object : TypeReference<MutableList<FeedItem>>() {}, mutableListOf())
    }

    override fun save(username: String, items: List<FeedItem>) = lock(username).withLock {
        store.writeJson(file(username), items)
    }

    override fun upsert(username: String, item: FeedItem): FeedItem = lock(username).withLock {
        val items = load(username)
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        store.writeJson(file(username), items)
        item
    }

    override fun delete(username: String, id: String): Boolean = lock(username).withLock {
        val items = load(username)
        val removed = items.removeAll { it.id == id }
        if (removed) store.writeJson(file(username), items)
        removed
    }
}
