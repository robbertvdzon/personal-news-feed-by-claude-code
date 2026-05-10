package com.vdzon.newsfeedbackend.request.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface RequestRepository {
    fun load(username: String): MutableList<NewsRequest>
    fun save(username: String, all: List<NewsRequest>)
    fun upsert(username: String, request: NewsRequest): NewsRequest
    fun delete(username: String, id: String): Boolean
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonRequestRepository(private val store: JsonStore) : RequestRepository {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private fun lock(u: String) = locks.computeIfAbsent(u) { ReentrantLock() }
    private fun file(u: String) = store.userFile(u, "news_requests.json")

    override fun load(username: String): MutableList<NewsRequest> = lock(username).withLock {
        store.readJsonRef(file(username), object : TypeReference<MutableList<NewsRequest>>() {}, mutableListOf())
    }

    override fun save(username: String, all: List<NewsRequest>) = lock(username).withLock {
        store.writeJson(file(username), all)
    }

    override fun upsert(username: String, request: NewsRequest): NewsRequest = lock(username).withLock {
        val all = load(username)
        val idx = all.indexOfFirst { it.id == request.id }
        if (idx >= 0) all[idx] = request else all.add(request)
        store.writeJson(file(username), all)
        request
    }

    override fun delete(username: String, id: String): Boolean = lock(username).withLock {
        val all = load(username)
        val removed = all.removeAll { it.id == id }
        if (removed) store.writeJson(file(username), all)
        removed
    }
}
