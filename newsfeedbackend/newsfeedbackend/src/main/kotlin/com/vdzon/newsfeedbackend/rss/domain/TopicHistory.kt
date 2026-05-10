package com.vdzon.newsfeedbackend.rss.domain

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class TopicEntry(
    val topic: String,
    val firstSeen: String = Instant.now().toString(),
    val lastSeenNews: String? = null,
    val lastSeenPodcast: String? = null,
    val newsCount: Int = 0,
    val podcastMentionCount: Int = 0,
    val podcastDeepCount: Int = 0,
    val likedCount: Int = 0,
    val starredCount: Int = 0
)

interface TopicHistoryRepository {
    fun load(username: String): MutableList<TopicEntry>
    fun save(username: String, entries: List<TopicEntry>)
    fun update(username: String, op: (MutableList<TopicEntry>) -> Unit)
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonTopicHistoryRepository(private val store: JsonStore) : TopicHistoryRepository {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private fun lock(u: String) = locks.computeIfAbsent(u) { ReentrantLock() }
    private fun file(u: String) = store.userFile(u, "topic_history.json")

    override fun load(username: String): MutableList<TopicEntry> = lock(username).withLock {
        store.readJsonRef(file(username), object : TypeReference<MutableList<TopicEntry>>() {}, mutableListOf())
    }

    override fun save(username: String, entries: List<TopicEntry>) = lock(username).withLock {
        store.writeJson(file(username), entries)
    }

    override fun update(username: String, op: (MutableList<TopicEntry>) -> Unit) = lock(username).withLock {
        val entries = load(username)
        op(entries)
        store.writeJson(file(username), entries)
    }
}
