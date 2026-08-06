package com.vdzon.newsfeedbackend.shared.api.dto

import com.vdzon.newsfeedbackend.settings.CategorySettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * SF-1992: borgt dat het publieke `GET /api/shared/categories` alleen
 * `id`, `name` en `enabled` prijsgeeft. De privé `extraInstructions` van de
 * bron-gebruiker (en het interne `isSystem`) mogen per constructie niet in
 * de JSON belanden.
 */
class SharedCategoryDtoTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `toSharedDto neemt alleen id, name en enabled over`() {
        val dto = CategorySettings(
            id = "kotlin",
            name = "Kotlin",
            enabled = true,
            extraInstructions = "Alleen artikelen over coroutines",
            isSystem = true
        ).toSharedDto()

        assertEquals(SharedCategoryDto(id = "kotlin", name = "Kotlin", enabled = true), dto)
    }

    @Test
    fun `toSharedDto behoudt enabled false`() {
        val dto = CategorySettings(id = "flutter", name = "Flutter", enabled = false).toSharedDto()

        assertFalse(dto.enabled)
    }

    @Test
    fun `serialisatie bevat precies de drie velden die de reader-app parseert`() {
        val json = mapper.writeValueAsString(
            SharedCategoryDto(id = "kotlin", name = "Kotlin", enabled = true)
        )

        @Suppress("UNCHECKED_CAST")
        val velden = mapper.readValue(json, Map::class.java) as Map<String, Any>
        assertEquals(setOf("id", "name", "enabled"), velden.keys)
        assertEquals("kotlin", velden["id"])
        assertEquals("Kotlin", velden["name"])
        assertTrue(velden["enabled"] as Boolean)
    }

    @Test
    fun `serialisatie lekt de prive extraInstructions niet`() {
        val geheim = "Alleen artikelen over coroutines"
        val json = mapper.writeValueAsString(
            CategorySettings(
                id = "kotlin",
                name = "Kotlin",
                extraInstructions = geheim,
                isSystem = true
            ).toSharedDto()
        )

        assertFalse(json.contains("extraInstructions"))
        assertFalse(json.contains(geheim))
        assertFalse(json.contains("isSystem"))
    }
}
