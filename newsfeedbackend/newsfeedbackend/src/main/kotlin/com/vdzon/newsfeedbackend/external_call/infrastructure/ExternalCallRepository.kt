package com.vdzon.newsfeedbackend.external_call.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.streams.toList as streamsToList

/**
 * Append-only JSONL persistentie voor [ExternalCall]-records.
 *
 * Eén regel = één JSON-object. Zoeken/aggregeren = file lezen en filteren
 * in-memory. Voor het verwachte volume (paar honderd calls/dag) is dit
 * prima; bij verhuizing naar Postgres mappen alle velden direct op een
 * tabel `external_calls`.
 */
@Component
class ExternalCallRepository(
    private val store: JsonStore,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    private fun file(): Path = store.root().resolve("external_calls.jsonl")

    fun append(call: ExternalCall) = lock.withLock {
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

    /**
     * Lees alle records, optioneel gefilterd op periode. Sorteer aflopend
     * op startTime zodat het meest recente bovenaan staat.
     */
    fun query(
        from: Instant? = null,
        to: Instant? = null,
        username: String? = null,
        provider: String? = null,
        action: String? = null,
        status: String? = null
    ): List<ExternalCall> = lock.withLock {
        val path = file()
        if (!Files.exists(path)) return emptyList()
        // Files.lines() levert een java.util.stream.Stream<String>; eerst materialiseren
        // naar List<String>, dan met de Kotlin-collection-API filteren/sorteren.
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

    fun all(): List<ExternalCall> = query()
}
