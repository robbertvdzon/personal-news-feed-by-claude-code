package com.vdzon.newsfeedbackend.rss.domain

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.storage.JsonStore
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

@Component
class TopicHistoryRepository(private val store: JsonStore) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private fun lock(u: String) = locks.computeIfAbsent(u) { ReentrantLock() }
    private fun file(u: String) = store.userFile(u, "topic_history.json")

    fun load(username: String): MutableList<TopicEntry> = lock(username).withLock {
        store.readJsonRef(file(username), object : TypeReference<MutableList<TopicEntry>>() {}, mutableListOf())
    }

    fun save(username: String, entries: List<TopicEntry>) = lock(username).withLock {
        store.writeJson(file(username), entries)
    }

    fun update(username: String, op: (MutableList<TopicEntry>) -> Unit) = lock(username).withLock {
        val entries = load(username)
        op(entries)
        store.writeJson(file(username), entries)
    }
}
