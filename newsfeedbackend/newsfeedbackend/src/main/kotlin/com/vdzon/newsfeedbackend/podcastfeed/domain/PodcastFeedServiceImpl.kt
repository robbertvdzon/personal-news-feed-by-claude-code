package com.vdzon.newsfeedbackend.podcastfeed.domain

import com.vdzon.newsfeedbackend.podcastfeed.InvalidPodcastFeedException
import com.vdzon.newsfeedbackend.podcastfeed.PodcastEpisode
import com.vdzon.newsfeedbackend.podcastfeed.PodcastFeed
import com.vdzon.newsfeedbackend.podcastfeed.PodcastFeedService
import com.vdzon.newsfeedbackend.podcastfeed.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.podcastfeed.infrastructure.PodcastFeedFetcher
import com.vdzon.newsfeedbackend.podcastfeed.infrastructure.PodcastFeedRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class PodcastFeedServiceImpl(
    private val feedRepo: PodcastFeedRepository,
    private val episodeRepo: PodcastEpisodeRepository,
    private val fetcher: PodcastFeedFetcher,
    private val events: ApplicationEventPublisher
) : PodcastFeedService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun list(username: String): List<PodcastFeed> = feedRepo.load(username)

    override fun save(username: String, feeds: List<PodcastFeed>): List<PodcastFeed> {
        // Normaliseer URLs (trim) en dedup zodat een dubbele invoer niet faalt op PK.
        val cleaned = feeds
            .map { it.copy(url = it.url.trim()) }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
        val existing = feedRepo.load(username).map { it.url }.toHashSet()
        val newOnes = cleaned.filter { it.url !in existing }

        // Synchrone validatie van nieuwe URLs (AC7) — bestaande URLs laten we
        // ongemoeid; die kunnen tijdelijk een fout-status hebben in de
        // pipeline-log maar moeten niet de save blokkeren.
        for (added in newOnes) {
            val result = fetcher.validate(added.url)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "onbekende fout"
                log.warn("[PodcastFeed] validatie afgewezen url={} msg={}", added.url, msg)
                throw InvalidPodcastFeedException(
                    added.url,
                    "Kon feed niet ophalen ($msg)"
                )
            }
        }

        val saved = feedRepo.save(username, cleaned)
        if (newOnes.isNotEmpty()) {
            log.info("[PodcastFeed] {} nieuwe bron(nen) opgeslagen voor '{}', trigger refresh", newOnes.size, username)
            events.publishEvent(PodcastRefreshRequested(username))
        }
        return saved
    }

    override fun delete(username: String, url: String): Boolean = feedRepo.delete(username, url)

    override fun triggerRefresh(username: String) {
        events.publishEvent(PodcastRefreshRequested(username))
    }

    override fun listEpisodes(username: String): List<PodcastEpisode> = episodeRepo.load(username)

    override fun resetStuck(): Int {
        val n = episodeRepo.resetStuck()
        if (n > 0) log.warn("[PodcastFeed] startup-reset: {} episode(s) van in-flight status naar FAILED gezet", n)
        return n
    }
}
