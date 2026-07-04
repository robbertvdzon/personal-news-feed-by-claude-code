package com.vdzon.newsfeedbackend.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiJsonTest {

    @Test
    fun `kale json blijft ongemoeid`() {
        assertEquals("""{"a": 1}""", AiJson.extract("""{"a": 1}"""))
    }

    @Test
    fun `markdown-fences worden gestript`() {
        assertEquals("""{"a": 1}""", AiJson.extract("```json\n{\"a\": 1}\n```"))
    }

    @Test
    fun `prose voor en na de json wordt verwijderd`() {
        assertEquals(
            """[{"id": "x"}]""",
            AiJson.extract("Hier is het resultaat:\n[{\"id\": \"x\"}]\nLaat weten of dit klopt!")
        )
    }

    @Test
    fun `array-opener wint van object-opener binnen de array`() {
        val input = """[{"id": 1}, {"id": 2}]"""
        assertEquals(input, AiJson.extract("antwoord: $input"))
    }

    @Test
    fun `brackets binnen strings tellen niet mee voor de depth`() {
        val input = """{"tekst": "een } binnen een string"}"""
        assertEquals(input, AiJson.extract(input))
    }

    @Test
    fun `escaped quotes binnen strings breken de parser niet`() {
        val input = """{"tekst": "hij zei \"hoi\""}"""
        assertEquals(input, AiJson.extract(input))
    }

    @Test
    fun `afgekapt antwoord geeft alles vanaf de opener terug`() {
        assertEquals("""{"a": [1, 2""", AiJson.extract("""bla {"a": [1, 2"""))
    }

    @Test
    fun `tekst zonder json komt onveranderd terug`() {
        assertEquals("geen json hier", AiJson.extract("geen json hier"))
    }
}
