package com.vdzon.newsfeedbackend.podcast_source

/**
 * Kleine façade rond de async podcast-ingestion zodat callers (zoals
 * de podcast-feeds-service in domain/, voor "trigger meteen na PUT")
 * niet de hele pipeline-klasse hoeven te kennen. Implementatie publiceert een
 * Spring-event dat de pipeline @Async oppakt.
 */
interface PodcastIngestionTrigger {
    fun trigger(username: String)
}
