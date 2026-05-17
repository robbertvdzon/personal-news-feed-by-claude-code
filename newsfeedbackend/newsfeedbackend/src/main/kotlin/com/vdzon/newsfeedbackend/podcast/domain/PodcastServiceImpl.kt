package com.vdzon.newsfeedbackend.podcast.domain

import com.vdzon.newsfeedbackend.podcast.CreatePodcastDto
import com.vdzon.newsfeedbackend.podcast.Podcast
import com.vdzon.newsfeedbackend.podcast.PodcastService
import com.vdzon.newsfeedbackend.podcast.PodcastStatus
import com.vdzon.newsfeedbackend.podcast.infrastructure.PodcastRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

@Service
class PodcastServiceImpl(
    private val repo: PodcastRepository,
    private val generator: PodcastGenerator
) : PodcastService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun list(username: String): List<Podcast> = repo.load(username)
        .map { it.copy(scriptText = null) }
        .sortedByDescending { it.createdAt }

    override fun get(username: String, id: String): Podcast? = repo.load(username).find { it.id == id }

    override fun create(username: String, dto: CreatePodcastDto): Podcast {
        val number = (repo.load(username).maxOfOrNull { it.podcastNumber } ?: 0) + 1
        val podcast = Podcast(
            id = UUID.randomUUID().toString(),
            title = "DevTalk $number, ${LocalDate.now()}",
            periodDescription = "afgelopen ${dto.periodDays} dagen",
            periodDays = dto.periodDays,
            durationMinutes = dto.durationMinutes,
            ttsProvider = dto.ttsProvider,
            customTopics = dto.customTopics,
            podcastNumber = number,
            status = PodcastStatus.PENDING
        )
        val saved = repo.upsert(username, podcast)
        log.info("[Podcast] created id={} for user '{}', dispatching async generator", saved.id, username)
        // Cross-bean call so the @Async proxy actually fires the
        // generation on a background thread.
        generator.generate(username, saved.id)
        return saved
    }

    override fun delete(username: String, id: String): Boolean = repo.delete(username, id)

    override fun audioBytes(username: String, id: String): ByteArray? {
        val bytes = repo.loadAudio(username, id)
        if (bytes != null) {
            log.info("[Podcast] audio read id={} user='{}' bytes={}", id, username, bytes.size)
        } else {
            log.warn("[Podcast] audio missing in db id={} user='{}'", id, username)
        }
        return bytes
    }
}
