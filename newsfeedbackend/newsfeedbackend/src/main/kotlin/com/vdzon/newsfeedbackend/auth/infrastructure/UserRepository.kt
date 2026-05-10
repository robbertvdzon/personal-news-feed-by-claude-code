package com.vdzon.newsfeedbackend.auth.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.auth.domain.User
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Repository voor [User]-records. Twee implementaties:
 *   * [JsonUserRepository] — actief bij `app.storage.backend=json` (default).
 *   * [PostgresUserRepository] — actief bij `app.storage.backend=postgres`.
 *
 * Spring picks de juiste op basis van de property; services injecteren
 * deze interface en hoeven dus niet te weten welk backend draait.
 */
interface UserRepository {
    fun load(): MutableList<User>
    fun findByUsername(username: String): User?
    fun add(user: User)
    fun update(user: User)
    fun deleteByUsername(username: String): Boolean
    fun usernames(): List<String>
    fun all(): List<User>
    fun hasAdmin(): Boolean
}

@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonUserRepository(private val store: JsonStore) : UserRepository {
    private val lock = ReentrantLock()

    private fun file() = store.root().resolve("users.json")

    override fun load(): MutableList<User> = lock.withLock {
        store.readJsonRef(file(), object : TypeReference<MutableList<User>>() {}, mutableListOf())
    }

    private fun save(users: List<User>) {
        store.writeJson(file(), users)
    }

    override fun findByUsername(username: String): User? = load().find { it.username == username }

    override fun add(user: User) = lock.withLock {
        val users = load()
        users.add(user)
        save(users)
    }

    override fun update(user: User) = lock.withLock {
        val users = load()
        val idx = users.indexOfFirst { it.username == user.username }
        if (idx >= 0) {
            users[idx] = user
            save(users)
        }
    }

    override fun deleteByUsername(username: String): Boolean = lock.withLock {
        val users = load()
        val removed = users.removeAll { it.username == username }
        if (removed) save(users)
        removed
    }

    override fun usernames(): List<String> = load().map { it.username }

    override fun all(): List<User> = load()

    override fun hasAdmin(): Boolean = load().any { it.role == User.ROLE_ADMIN }
}
