package com.vdzon.newsfeedbackend.auth.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.auth.domain.User
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class UserRepository(private val store: JsonStore) {
    private val lock = ReentrantLock()

    private fun file() = store.root().resolve("users.json")

    fun load(): MutableList<User> = lock.withLock {
        store.readJsonRef(file(), object : TypeReference<MutableList<User>>() {}, mutableListOf())
    }

    private fun save(users: List<User>) {
        store.writeJson(file(), users)
    }

    fun findByUsername(username: String): User? = load().find { it.username == username }

    fun add(user: User) = lock.withLock {
        val users = load()
        users.add(user)
        save(users)
    }

    fun usernames(): List<String> = load().map { it.username }
}
