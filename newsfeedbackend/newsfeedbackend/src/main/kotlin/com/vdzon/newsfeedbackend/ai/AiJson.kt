package com.vdzon.newsfeedbackend.ai

/**
 * Haalt de JSON-payload uit een LLM-antwoord.
 *
 * Modellen wikkelen hun antwoord regelmatig in ```json ... ```-fences en
 * plakken er soms prose omheen. We strippen eerst de fences, zoeken dan
 * de vroegste openings-bracket — `[` voor een array, `{` voor een object —
 * en lopen vooruit met een depth-teller (strings + escapes gerespecteerd)
 * tot de bijbehorende sluit-bracket. Dat geeft een gebalanceerd segment,
 * ook wanneer er tekst achter de JSON staat.
 *
 * Sluit de depth nooit (antwoord afgekapt door max_tokens), dan geven we
 * alles vanaf de opener terug — de caller krijgt dan een Jackson-parse-
 * fout en logt de ruwe tekst voor debugging.
 *
 * Stond eerst als privékopie in vier pipelines (rss, events ×2,
 * podcast_source); één geteste implementatie voorkomt dat een bug vier
 * keer gefixt moet worden.
 */
object AiJson {

    fun extract(text: String): String {
        var s = text.trim()
        // Strip leading ```json of ``` fence
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
        // Strip trailing ``` fence
        if (s.endsWith("```")) s = s.dropLast(3).trim()

        val curly = s.indexOf('{')
        val bracket = s.indexOf('[')
        val start = when {
            curly < 0 && bracket < 0 -> return s
            curly < 0 -> bracket
            bracket < 0 -> curly
            else -> minOf(curly, bracket)
        }
        val openChar = s[start]
        val closeChar = if (openChar == '{') '}' else ']'

        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return s.substring(start)
    }
}
