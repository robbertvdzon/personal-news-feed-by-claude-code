package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.podcast.infrastructure.TtsClient

/**
 * Pure parser for podcast script text → speaker segments.
 *
 * Canonical format is `INTERVIEWER:` / `GAST:` per line, but the LLM
 * sometimes emits variations. We tolerate:
 *   - Markdown wrappers: `**INTERVIEWER:**`, `*Gast:*`, `### Interviewer:`
 *   - Lower/mixed case: `Interviewer:`, `gast:`
 *   - Aliases: `Host`, `Moderator`, `Presentator` → INTERVIEWER;
 *              `Guest`, `Expert` → GUEST
 *   - Positional labels: `Spreker 1`/`Spreker 2`, `Speaker 1`/`Speaker 2`
 *     are mapped in first-encountered order to INTERVIEWER/GUEST.
 *
 * Stage directions (lines fully wrapped in `(...)` / `[...]`, or pure
 * horizontal rules like `---`) are silently skipped and do NOT count
 * toward the parse stats — they are not real dialogue lines.
 *
 * The `Result` carries diagnostics so the caller can distinguish a
 * parser miss (matched == 0 while totalContentLines > 0) from a TTS
 * miss (matched > 0 but later TTS calls produced no audio).
 */
object PodcastScriptParser {

    data class Segment(val role: TtsClient.SpeakerRole, val text: String)

    data class Result(
        val segments: List<Segment>,
        val totalContentLines: Int,
        val matchedLines: Int
    )

    private val INTERVIEWER_ALIASES = setOf(
        "interviewer", "host", "moderator", "presentator", "presenter"
    )

    private val GUEST_ALIASES = setOf(
        "gast", "guest", "expert"
    )

    private val POSITIONAL_LABEL = Regex("""^(spreker|speaker|voice|stem|persoon|person)\s*([12])$""")

    fun parse(script: String): Result {
        var totalContentLines = 0
        var matchedLines = 0
        val segments = mutableListOf<Segment>()
        val positionalToRole = linkedMapOf<String, TtsClient.SpeakerRole>()

        for (rawLine in script.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (isStageDirection(line)) continue

            totalContentLines += 1

            val (label, text) = splitLabelAndText(line) ?: continue
            if (text.isBlank()) continue
            val role = resolveRole(label, positionalToRole) ?: continue

            matchedLines += 1
            segments += Segment(role, text)
        }

        return Result(segments, totalContentLines, matchedLines)
    }

    private fun isStageDirection(line: String): Boolean {
        if (line.startsWith("(") && line.endsWith(")")) return true
        if (line.startsWith("[") && line.endsWith("]")) return true
        if (line.matches(Regex("^[-—*_=]{3,}$"))) return true
        return false
    }

    private fun splitLabelAndText(line: String): Pair<String, String>? {
        var working = line
        while (working.isNotEmpty() && working[0] in "*_#>- \t") {
            working = working.substring(1)
        }
        val colonIdx = working.indexOf(':')
        if (colonIdx <= 0) return null
        val label = working.substring(0, colonIdx).trim().trimEnd('*', '_', ' ', '\t')
        if (label.isEmpty()) return null
        val text = working.substring(colonIdx + 1).trim().trimStart('*', '_', ' ', '\t').trimEnd('*', '_').trim()
        return label to text
    }

    private fun resolveRole(
        label: String,
        positionalToRole: MutableMap<String, TtsClient.SpeakerRole>
    ): TtsClient.SpeakerRole? {
        val normalized = label.lowercase().trim()
        if (normalized in INTERVIEWER_ALIASES) return TtsClient.SpeakerRole.INTERVIEWER
        if (normalized in GUEST_ALIASES) return TtsClient.SpeakerRole.GUEST

        val match = POSITIONAL_LABEL.find(normalized) ?: return null
        val key = "${match.groupValues[1]} ${match.groupValues[2]}"
        positionalToRole[key]?.let { return it }
        val role = when {
            positionalToRole.isEmpty() -> TtsClient.SpeakerRole.INTERVIEWER
            positionalToRole.size == 1 && !positionalToRole.containsValue(TtsClient.SpeakerRole.GUEST) ->
                TtsClient.SpeakerRole.GUEST
            else -> return null
        }
        positionalToRole[key] = role
        return role
    }
}
