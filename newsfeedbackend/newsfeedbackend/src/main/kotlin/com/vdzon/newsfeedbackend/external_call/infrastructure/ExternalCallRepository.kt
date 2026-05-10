package com.vdzon.newsfeedbackend.external_call.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.streams.toList as streamsToList

interface ExternalCallRepository {
    fun append(call: ExternalCall)
    fun query(
        from: Instant? = null,
        to: Instant? = null,
        username: String? = null,
        provider: String? = null,
        action: String? = null,
        status: String? = null
    ): List<ExternalCall>
    fun all(): List<ExternalCall>
}

/**
 * Append-only JSONL persistentie voor [ExternalCall]-records.
 * Eén regel = één JSON-object.
 */
@Component
@ConditionalOnProperty(name = ["app.storage.backend"], havingValue = "json", matchIfMissing = true)
class JsonExternalCallRepository(
    private val store: JsonStore,
    private val mapper: ObjectMapper
) : ExternalCallRepository {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    private fun file(): Path = store.root().resolve("external_calls.jsonl")

    override fun append(call: ExternalCall) {
        lock.withLock {
            try {
                val path = file()
                Files.createDirectories(path.parent)
                val line = mapper.writeValueAsString(call) + "\n"
                Files.write(
                    path,
                    line.toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
            } catch (e: Exception) {
                log.warn("[ExternalCalls] could not append to log: {}", e.message)
            }
        }
    }

    override fun query(
        from: Instant?,
        to: Instant?,
        username: String?,
        provider: String?,
        action: String?,
        status: String?
    ): List<ExternalCall> = lock.withLock {
        val path = file()
        if (!Files.exists(path)) return emptyList()
        val lines: List<String> = Files.lines(path).use { it.streamsToList() }
        return lines
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    mapper.readValue(line, ExternalCall::class.java)
                } catch (e: Exception) {
                    log.warn("[ExternalCalls] skipping malformed line: {}", e.message)
                    null
                }
            }
            .filter { call ->
                (from == null || !call.startTime.isBefore(from)) &&
                    (to == null || call.startTime.isBefore(to)) &&
                    (username == null || call.username == username) &&
                    (provider == null || call.provider == provider) &&
                    (action == null || call.action == action) &&
                    (status == null || call.status == status)
            }
            .sortedByDescending { it.startTime }
    }

    override fun all(): List<ExternalCall> = query()
}
