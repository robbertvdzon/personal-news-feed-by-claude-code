package com.vdzon.newsfeedbackend.test.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class IsolationMarkerRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun getAllMarkers(): List<Map<String, Any?>> {
        return jdbc.queryForList(
            "SELECT id, message, created_at FROM kan55_isolation_marker ORDER BY id",
            emptyMap<String, Any>()
        )
    }
}
