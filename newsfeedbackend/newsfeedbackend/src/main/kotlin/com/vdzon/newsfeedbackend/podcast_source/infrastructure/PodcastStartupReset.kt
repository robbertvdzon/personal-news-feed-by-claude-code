package com.vdzon.newsfeedbackend.podcast_source.infrastructure

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PodcastStartupReset(
    private val episodeRepo: PodcastEpisodeRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun resetFailedEpisodesOnStartup() {
        try {
            val resetCount = episodeRepo.resetFailedWithOomError()
            if (resetCount > 0) {
                log.info("[PodcastStartup] reset {} FAILED-rijen met OOM/download-errors naar PENDING", resetCount)
            }
        } catch (e: Exception) {
            log.warn("[PodcastStartup] kon OOM-failed-episodes niet resetten: {}", e.message)
        }
    }
}
