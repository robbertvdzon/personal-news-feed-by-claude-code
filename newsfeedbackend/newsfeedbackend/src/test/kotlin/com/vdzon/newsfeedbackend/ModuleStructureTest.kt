package com.vdzon.newsfeedbackend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Bewaakt de Spring Modulith module-grenzen als "ratchet": de schendingen
 * in [KNOWN_VIOLATIONS] bestonden al toen deze test werd geïntroduceerd
 * (zie docs/kwaliteitsanalyse-backend.md) en worden in fase 2 één voor
 * één weggewerkt. Elke NIEUWE schending laat deze test falen.
 *
 * Werk je een bekende schending weg? Verwijder dan ook de regel uit de
 * allowlist zodat hij niet ongemerkt terug kan komen.
 */
class ModuleStructureTest {

    companion object {
        private val KNOWN_VIOLATIONS: List<String> = listOf(
            // Leeg — alle schendingen uit de kwaliteitsanalyse zijn in
            // fase 2 weggewerkt. Houd dit leeg: nieuwe schendingen los je
            // op, tenzij er een zwaarwegende reden is om er één te
            // motiveren en hier toe te voegen.
        )
    }

    @Test
    fun `geen nieuwe schendingen van module-grenzen`() {
        val modules = ApplicationModules.of(Application::class.java)
        val messages = modules.detectViolations().messages

        val nieuw = messages.filterNot { msg -> KNOWN_VIOLATIONS.any { msg.contains(it) } }
        assertTrue(
            nieuw.isEmpty(),
            "Nieuwe module-schendingen gevonden (los op, of motiveer + voeg toe aan KNOWN_VIOLATIONS):\n" +
                nieuw.joinToString("\n\n")
        )
    }
}
