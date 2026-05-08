package com.vdzon.newsfeedbackend.storage

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Simple JSON file persistence helper.
 *
 * Atomic writes: write to a temp file then rename.
 * Per-file locks: writes to the same file are serialised.
 */
@Component
class JsonStore(
    private val objectMapper: ObjectMapper,
    @Value("\${app.data-dir:./data}") private val dataDir: String
) {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun root(): Path = Path.of(dataDir).also { Files.createDirectories(it) }

    fun userDir(username: String): Path = root().resolve("users").resolve(username).also {
        Files.createDirectories(it)
    }

    fun userFile(username: String, name: String): Path = userDir(username).resolve(name)

    fun objectMapper(): ObjectMapper = objectMapper

    fun <T> readJson(path: Path, type: Class<T>, default: T): T {
        if (!Files.exists(path)) return default
        return objectMapper.readValue(Files.readAllBytes(path), type)
    }

    fun <T> readJsonRef(path: Path, ref: com.fasterxml.jackson.core.type.TypeReference<T>, default: T): T {
        if (!Files.exists(path)) return default
        return objectMapper.readValue(Files.readAllBytes(path), ref)
    }

    fun writeJson(path: Path, value: Any) {
        val key = path.toAbsolutePath().toString()
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        lock.withLock {
            Files.createDirectories(path.parent)
            val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value)
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}
