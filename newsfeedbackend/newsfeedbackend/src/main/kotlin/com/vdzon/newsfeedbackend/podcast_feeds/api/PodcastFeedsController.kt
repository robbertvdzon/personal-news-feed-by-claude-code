package com.vdzon.newsfeedbackend.podcast_feeds.api

import com.vdzon.newsfeedbackend.common.SecurityHelpers
import com.vdzon.newsfeedbackend.podcast_feeds.PodcastFeedsSettings
import com.vdzon.newsfeedbackend.podcast_feeds.domain.PodcastEpisodePipeline
import com.vdzon.newsfeedbackend.podcast_feeds.infrastructure.PodcastEpisodesRepository
import com.vdzon.newsfeedbackend.podcast_feeds.infrastructure.PodcastFeedsRepository
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/podcast-feeds")
class PodcastFeedsController(
    private val feedsRepo: PodcastFeedsRepository,
    private val episodesRepo: PodcastEpisodesRepository,
    private val settings: SettingsService,
    private val pipeline: PodcastEpisodePipeline
) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping
    fun list(): PodcastFeedsSettings = settings.getPodcastFeeds(user())

    @PostMapping
    fun add(@RequestBody req: AddPodcastFeedRequest): Map<String, String> {
        val username = user()
        val current = settings.getPodcastFeeds(username)
        val updated = current.copy(
            feeds = current.feeds + PodcastFeedsSettings.Feed(
                url = req.feedUrl,
                transcribeEnabled = req.transcribeEnabled ?: true
            )
        )
        settings.savePodcastFeeds(username, updated)
        return mapOf("status" to "ok")
    }

    @DeleteMapping
    fun remove(@RequestParam feedUrl: String): Map<String, String> {
        val username = user()
        val current = settings.getPodcastFeeds(username)
        val updated = current.copy(
            feeds = current.feeds.filter { it.url != feedUrl }
        )
        settings.savePodcastFeeds(username, updated)
        return mapOf("status" to "ok")
    }

    @PostMapping("/refresh")
    fun refresh(): Map<String, String> {
        pipeline.run(user())
        return mapOf("status" to "ok")
    }

    @GetMapping("/episodes")
    fun episodes(): Map<String, Any> {
        val username = user()
        val all = episodesRepo.load(username)
        return mapOf(
            "episodes" to all,
            "total" to all.size,
            "done" to all.count { it.status == "DONE" },
            "pending" to all.count { it.status in listOf("PENDING", "DOWNLOADING", "TRANSCRIBING", "SUMMARIZING") }
        )
    }
}

data class AddPodcastFeedRequest(
    val feedUrl: String,
    val transcribeEnabled: Boolean? = true
)
