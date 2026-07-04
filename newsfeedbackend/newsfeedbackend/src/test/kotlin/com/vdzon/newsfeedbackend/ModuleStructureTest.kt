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
            // Cycle feed → settings → podcast_source → rss → feed; wordt in
            // fase 2 gebroken (SettingsController via service + events i.p.v.
            // directe repo-toegang vanuit podcast_source).
            "Cycle detected: Slice feed",
            "Cycle detected: Slice podcast_source",
            // admin grijpt rechtstreeks in auth/external_call-internals.
            "Module 'admin' depends on non-exposed type com.vdzon.newsfeedbackend.auth.domain.User",
            "Module 'admin' depends on non-exposed type com.vdzon.newsfeedbackend.auth.infrastructure.UserRepository",
            "Module 'admin' depends on non-exposed type com.vdzon.newsfeedbackend.external_call.infrastructure.ExternalCallRepository",
            // events hergebruikt infrastructuur van podcast_source/request.
            "Module 'events' depends on non-exposed type com.vdzon.newsfeedbackend.podcast_source.infrastructure.AudioTranscoder",
            "Module 'events' depends on non-exposed type com.vdzon.newsfeedbackend.podcast_source.infrastructure.WhisperClient",
            "Module 'events' depends on non-exposed type com.vdzon.newsfeedbackend.request.infrastructure.TavilyClient",
            "Module 'events' depends on non-exposed type com.vdzon.newsfeedbackend.request.infrastructure.TavilyResult",
            // podcast leest episodes rechtstreeks uit podcast_source.
            "Module 'podcast' depends on non-exposed type com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository",
            // de rss↔podcast_source-verstrengeling (fase 2.2).
            "Module 'podcast_source' depends on non-exposed type com.vdzon.newsfeedbackend.rss.domain.PodcastPromotionRequested",
            "Module 'podcast_source' depends on non-exposed type com.vdzon.newsfeedbackend.rss.domain.RssRefreshRequested",
            "Module 'podcast_source' depends on non-exposed type com.vdzon.newsfeedbackend.rss.infrastructure.RssItemRepository",
            // settings valideert/triggert podcast-feeds via infrastructuur (fase 2.4).
            "Module 'settings' depends on non-exposed type com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastFeedFetcher"
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
