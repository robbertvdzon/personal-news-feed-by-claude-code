package com.vdzon.newsfeedbackend.auth.infrastructure

import com.vdzon.newsfeedbackend.auth.domain.User
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * Postgres-backend voor users. Schema: `users(id PK, username unique,
 * password_hash, role, created_at)`. Eén globale tabel — geen
 * partitionering per user.
 */
@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "postgres")
class PostgresUserRepository(private val jdbc: NamedParameterJdbcTemplate) : UserRepository {

    private val rowMapper = { rs: java.sql.ResultSet, _: Int ->
        User(
            id = rs.getString("id"),
            username = rs.getString("username"),
            passwordHash = rs.getString("password_hash"),
            role = rs.getString("role")
        )
    }

    override fun load(): MutableList<User> =
        jdbc.query("SELECT id, username, password_hash, role FROM users ORDER BY username", rowMapper).toMutableList()

    override fun findByUsername(username: String): User? =
        jdbc.query(
            "SELECT id, username, password_hash, role FROM users WHERE username = :u",
            MapSqlParameterSource("u", username),
            rowMapper
        ).firstOrNull()

    override fun add(user: User) {
        jdbc.update(
            """
            INSERT INTO users (id, username, password_hash, role)
            VALUES (:id, :username, :passwordHash, :role)
            """,
            MapSqlParameterSource()
                .addValue("id", user.id)
                .addValue("username", user.username)
                .addValue("passwordHash", user.passwordHash)
                .addValue("role", user.role)
        )
    }

    override fun update(user: User) {
        jdbc.update(
            """
            UPDATE users
            SET id = :id, password_hash = :passwordHash, role = :role
            WHERE username = :username
            """,
            MapSqlParameterSource()
                .addValue("id", user.id)
                .addValue("username", user.username)
                .addValue("passwordHash", user.passwordHash)
                .addValue("role", user.role)
        )
    }

    override fun deleteByUsername(username: String): Boolean {
        val n = jdbc.update(
            "DELETE FROM users WHERE username = :u",
            MapSqlParameterSource("u", username)
        )
        return n > 0
    }

    override fun usernames(): List<String> =
        jdbc.queryForList("SELECT username FROM users ORDER BY username", emptyMap<String, Any>(), String::class.java)

    override fun all(): List<User> = load()

    override fun hasAdmin(): Boolean =
        (jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = :r",
            MapSqlParameterSource("r", User.ROLE_ADMIN),
            Long::class.java
        ) ?: 0L) > 0L
}
