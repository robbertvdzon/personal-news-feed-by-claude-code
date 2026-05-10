package com.vdzon.newsfeedbackend.storage

import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * Helper voor Postgres-repos om Kotlin-collecties als JSONB op te slaan
 * en weer in te lezen. PgJDBC eist een [PGobject] met type "jsonb"
 * (anders weigert 'ie 'm in een jsonb-kolom te zetten).
 *
 * NB: PGobject's setters gooien SQLException; daardoor "ziet" Kotlin ze
 * niet als property — we roepen ze expliciet aan.
 */
@Component
class JdbcJsonb(private val mapper: ObjectMapper) {

    /** Serialise naar een PGobject van type "jsonb". `null` → JSON null. */
    fun toJsonb(value: Any?): PGobject {
        val pg = PGobject()
        pg.setType("jsonb")
        pg.setValue(if (value == null) "null" else mapper.writeValueAsString(value))
        return pg
    }

    /** Lees jsonb-kolom als List<T>. Lege/null → emptyList. */
    fun <T> readList(rs: ResultSet, col: String, elementClass: Class<T>): List<T> {
        val raw = rs.getString(col) ?: return emptyList()
        if (raw.isBlank() || raw == "null") return emptyList()
        val type = mapper.typeFactory.constructCollectionType(List::class.java, elementClass)
        return mapper.readValue(raw, type)
    }

    /** Lees jsonb-kolom als arbitrary T. */
    fun <T> readObj(rs: ResultSet, col: String, clazz: Class<T>): T? {
        val raw = rs.getString(col) ?: return null
        if (raw.isBlank() || raw == "null") return null
        return mapper.readValue(raw, clazz)
    }
}
