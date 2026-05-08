package com.vdzon.newsfeedbackend.request.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class RequestRepository(private val store: JsonStore) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private fun lock(u: String) = locks.computeIfAbsent(u) { ReentrantLock() }
    private fun file(u: String) = store.userFile(u, "news_requests.json")

    fun load(username: String): MutableList<NewsRequest> = lock(username).withLock {
        store.readJsonRef(file(username), object : TypeReference<MutableList<NewsRequest>>() {}, mutableListOf())
    }

    fun save(username: String, all: List<NewsRequest>) = lock(username).withLock {
        store.writeJson(file(username), all)
    }

    fun upsert(username: String, request: NewsRequest): NewsRequest = lock(username).withLock {
        val all = load(username)
        val idx = all.indexOfFirst { it.id == request.id }
        if (idx >= 0) all[idx] = request else all.add(request)
        store.writeJson(file(username), all)
        request
    }

    fun delete(username: String, id: String): Boolean = lock(username).withLock {
        val all = load(username)
        val removed = all.removeAll { it.id == id }
        if (removed) store.writeJson(file(username), all)
        removed
    }
}
