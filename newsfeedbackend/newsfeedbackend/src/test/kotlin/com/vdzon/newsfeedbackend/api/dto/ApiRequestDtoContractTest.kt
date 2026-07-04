package com.vdzon.newsfeedbackend.api.dto

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.vdzon.newsfeedbackend.admin.api.dto.ResetPasswordRequest
import com.vdzon.newsfeedbackend.admin.api.dto.SetRoleRequest
import com.vdzon.newsfeedbackend.auth.api.dto.ChangePasswordRequest
import com.vdzon.newsfeedbackend.events.api.dto.VideoSummaryRequest
import com.vdzon.newsfeedbackend.settings.api.dto.AddEventPreferenceRequest
import com.vdzon.newsfeedbackend.settings.api.dto.RemoveEventPreferenceRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SF-437: borgt het JSON-wire-contract van de request-DTO's die naar
 * `module/api/dto/` zijn verplaatst. De verhuizing mag puur mechanisch zijn:
 * dezelfde veldnamen deserialiseren identiek, ongeacht het package.
 */
class ApiRequestDtoContractTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `ChangePasswordRequest deserialiseert currentPassword en newPassword`() {
        val dto = mapper.readValue(
            """{"currentPassword":"old","newPassword":"new"}""",
            ChangePasswordRequest::class.java
        )
        assertEquals("old", dto.currentPassword)
        assertEquals("new", dto.newPassword)
    }

    @Test
    fun `ResetPasswordRequest deserialiseert newPassword`() {
        val dto = mapper.readValue("""{"newPassword":"secret"}""", ResetPasswordRequest::class.java)
        assertEquals("secret", dto.newPassword)
    }

    @Test
    fun `SetRoleRequest deserialiseert role`() {
        val dto = mapper.readValue("""{"role":"ROLE_ADMIN"}""", SetRoleRequest::class.java)
        assertEquals("ROLE_ADMIN", dto.role)
    }

    @Test
    fun `AddEventPreferenceRequest deserialiseert name`() {
        val dto = mapper.readValue("""{"name":"AI"}""", AddEventPreferenceRequest::class.java)
        assertEquals("AI", dto.name)
    }

    @Test
    fun `RemoveEventPreferenceRequest deserialiseert name`() {
        val dto = mapper.readValue("""{"name":"AI"}""", RemoveEventPreferenceRequest::class.java)
        assertEquals("AI", dto.name)
    }

    @Test
    fun `VideoSummaryRequest deserialiseert videoUrl`() {
        val dto = mapper.readValue(
            """{"videoUrl":"https://youtu.be/x"}""",
            VideoSummaryRequest::class.java
        )
        assertEquals("https://youtu.be/x", dto.videoUrl)
    }
}
