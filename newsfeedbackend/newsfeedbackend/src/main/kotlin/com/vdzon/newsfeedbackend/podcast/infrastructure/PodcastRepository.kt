package com.vdzon.newsfeedbackend.podcast.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface PodcastRepository {
    fun load(username: String): MutableList<Podcast>
    fun save(username: String, all: List<Podcast>)
    fun upsert(username: String, podcast: Podcast): Podcast
    fun delete(username: String, id: String): Boolean
    fun audioPath(username: String, podcastId: String): Path
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonPodcastRepository(private val store: JsonStore) : PodcastRepository {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private fun lock(u: String) = locks.computeIfAbsent(u) { ReentrantLock() }
    private fun file(u: String) = store.userFile(u, "podcasts.json")

    override fun audioPath(username: String, podcastId: String): Path {
        val dir = store.userDir(username).resolve("audio")
        Files.createDirectories(dir)
        return dir.resolve("$podcastId.mp3")
    }

    override fun load(username: String): MutableList<Podcast> = lock(username).withLock {
        store.readJsonRef(file(username), object : TypeReference<MutableList<Podcast>>() {}, mutableListOf())
    }

    override fun save(username: String, all: List<Podcast>) = lock(username).withLock {
        store.writeJson(file(username), all)
    }

    override fun upsert(username: String, podcast: Podcast): Podcast = lock(username).withLock {
        val all = load(username)
        val idx = all.indexOfFirst { it.id == podcast.id }
        if (idx >= 0) all[idx] = podcast else all.add(podcast)
        store.writeJson(file(username), all)
        podcast
    }

    override fun delete(username: String, id: String): Boolean = lock(username).withLock {
        val all = load(username)
        val removed = all.removeAll { it.id == id }
        if (removed) {
            store.writeJson(file(username), all)
            Files.deleteIfExists(audioPath(username, id))
        }
        removed
    }
}
