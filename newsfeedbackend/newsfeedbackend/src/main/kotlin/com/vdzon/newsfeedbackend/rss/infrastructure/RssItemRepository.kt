package com.vdzon.newsfeedbackend.rss.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface RssItemRepository {
    fun load(username: String): MutableList<RssItem>
    fun save(username: String, items: List<RssItem>)
    fun upsert(username: String, item: RssItem): RssItem
    fun upsertAll(username: String, batch: List<RssItem>)
    fun delete(username: String, id: String): Boolean
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonRssItemRepository(private val store: JsonStore) : RssItemRepository {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun file(username: String) = store.userFile(username, "rss_items.json")
    private fun lock(username: String) = locks.computeIfAbsent(username) { ReentrantLock() }

    override fun load(username: String): MutableList<RssItem> = lock(username).withLock {
        store.readJsonRef(file(username), object : TypeReference<MutableList<RssItem>>() {}, mutableListOf())
    }

    override fun save(username: String, items: List<RssItem>) = lock(username).withLock {
        store.writeJson(file(username), items)
    }

    override fun upsert(username: String, item: RssItem): RssItem = lock(username).withLock {
        val items = load(username)
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        store.writeJson(file(username), items)
        item
    }

    override fun upsertAll(username: String, batch: List<RssItem>) = lock(username).withLock {
        val items = load(username)
        val byId = items.associateBy { it.id }.toMutableMap()
        batch.forEach { byId[it.id] = it }
        val list = byId.values.toMutableList()
        store.writeJson(file(username), list)
    }

    override fun delete(username: String, id: String): Boolean = lock(username).withLock {
        val items = load(username)
        val removed = items.removeAll { it.id == id }
        if (removed) store.writeJson(file(username), items)
        removed
    }
}
