package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.podcast.infrastructure.TtsClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PodcastScriptParserTest {

    @Test
    fun `canonical INTERVIEWER GAST script parses cleanly`() {
        val script = """
            INTERVIEWER: Welkom bij DevTalk.
            GAST: Dank je, leuk om hier te zijn.
            INTERVIEWER: Wat is recent het belangrijkste nieuws?
            GAST: AI redeneer-modellen maken een sprong.
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        assertEquals(4, result.totalContentLines)
        assertEquals(4, result.matchedLines)
        assertEquals(4, result.segments.size)
        assertEquals(TtsClient.SpeakerRole.INTERVIEWER, result.segments[0].role)
        assertEquals("Welkom bij DevTalk.", result.segments[0].text)
        assertEquals(TtsClient.SpeakerRole.GUEST, result.segments[1].role)
    }

    @Test
    fun `markdown wrappers around speaker labels are tolerated`() {
        val script = """
            **INTERVIEWER:** Hoi.
            **GAST**: Hallo.
            *Interviewer:* Tweede vraag.
            ### GAST: Tweede antwoord.
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        assertEquals(4, result.matchedLines)
        assertEquals("Hoi.", result.segments[0].text)
        assertEquals(TtsClient.SpeakerRole.GUEST, result.segments[1].role)
        assertEquals("Hallo.", result.segments[1].text)
        assertEquals(TtsClient.SpeakerRole.INTERVIEWER, result.segments[2].role)
        assertEquals("Tweede vraag.", result.segments[2].text)
        assertEquals(TtsClient.SpeakerRole.GUEST, result.segments[3].role)
    }

    @Test
    fun `Dutch and English aliases map to canonical roles`() {
        val script = """
            Host: Welkom.
            Expert: Bedankt.
            Moderator: En verder?
            Guest: Goede vraag.
            Presentator: Tot slot.
            Gast: Tot ziens.
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        assertEquals(6, result.matchedLines)
        val roles = result.segments.map { it.role }
        assertEquals(
            listOf(
                TtsClient.SpeakerRole.INTERVIEWER,
                TtsClient.SpeakerRole.GUEST,
                TtsClient.SpeakerRole.INTERVIEWER,
                TtsClient.SpeakerRole.GUEST,
                TtsClient.SpeakerRole.INTERVIEWER,
                TtsClient.SpeakerRole.GUEST,
            ),
            roles
        )
    }

    @Test
    fun `positional Spreker 1 2 labels map in encounter order`() {
        val script = """
            Spreker 1: Hallo.
            Spreker 2: Hoi.
            Spreker 1: Vraag?
            Spreker 2: Antwoord.
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        assertEquals(4, result.matchedLines)
        assertEquals(TtsClient.SpeakerRole.INTERVIEWER, result.segments[0].role)
        assertEquals(TtsClient.SpeakerRole.GUEST, result.segments[1].role)
        assertEquals(TtsClient.SpeakerRole.INTERVIEWER, result.segments[2].role)
        assertEquals(TtsClient.SpeakerRole.GUEST, result.segments[3].role)
    }

    @Test
    fun `positional labels respect first-encountered order even when Spreker 2 comes first`() {
        val script = """
            Speaker 2: Eerste regel.
            Speaker 1: Tweede regel.
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        assertEquals(2, result.matchedLines)
        // Eerste label encountered = INTERVIEWER, ongeacht het cijfer.
        assertEquals(TtsClient.SpeakerRole.INTERVIEWER, result.segments[0].role)
        assertEquals(TtsClient.SpeakerRole.GUEST, result.segments[1].role)
    }

    @Test
    fun `stage directions and separators are silently skipped`() {
        val script = """
            (intro muziek)
            [pauze]
            ---
            INTERVIEWER: Welkom.
            GAST: Bedankt.
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        // Stage directions tellen niet als content lines.
        assertEquals(2, result.totalContentLines)
        assertEquals(2, result.matchedLines)
    }

    @Test
    fun `script without any recognisable labels yields empty result with diagnostics`() {
        val script = """
            Welkom bij de podcast.
            Vandaag bespreken we AI.
            Dat is interessant.
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        assertEquals(3, result.totalContentLines)
        assertEquals(0, result.matchedLines)
        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun `empty label or empty text are not matched`() {
        val script = """
            INTERVIEWER:
            : geen label hier
            GAST: echte tekst
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        assertEquals(1, result.matchedLines)
        assertEquals(TtsClient.SpeakerRole.GUEST, result.segments[0].role)
        assertEquals("echte tekst", result.segments[0].text)
    }

    @Test
    fun `unknown label is skipped without breaking the rest`() {
        val script = """
            INTERVIEWER: Eerste vraag.
            JINGLE: bing bong
            GAST: Antwoord.
        """.trimIndent()

        val result = PodcastScriptParser.parse(script)

        assertEquals(3, result.totalContentLines)
        assertEquals(2, result.matchedLines)
        assertEquals(TtsClient.SpeakerRole.INTERVIEWER, result.segments[0].role)
        assertEquals(TtsClient.SpeakerRole.GUEST, result.segments[1].role)
    }
}
