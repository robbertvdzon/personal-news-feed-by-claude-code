package com.vdzon.newsfeedbackend.podcast_feeds.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.feed.FeedItem
import com.vdzon.newsfeedbackend.feed.FeedService
import com.vdzon.newsfeedbackend.podcast_feeds.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_feeds.infrastructure.PodcastEpisodesRepository
import com.vdzon.newsfeedbackend.podcast_feeds.infrastructure.PodcastFetcher
import com.vdzon.newsfeedbackend.podcast_feeds.infrastructure.PodcastFeedsRepository
import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

@Component
class PodcastEpisodePipeline(
    private val podcastFeedsRepo: PodcastFeedsRepository,
    private val episodesRepo: PodcastEpisodesRepository,
    private val fetcher: PodcastFetcher,
    private val anthropic: AnthropicClient,
    private val settings: SettingsService,
    private val feed: FeedService,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry,
    private val callLogger: ExternalCallLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    @EventListener
    @Async
    fun onRefresh(event: PodcastRefreshRequested) = run(event.username)

    fun run(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[PODCAST] refresh already running for '{}'", username)
            return
        }
        MDC.put("username", username)
        try {
            log.info("[PODCAST] start hourly podcast refresh for '{}'", username)
            val started = Instant.now()

            val feedSettings = settings.getPodcastFeeds(username)
            val categories = settings.getCategories(username).filter { it.enabled || it.isSystem }

            log.info("[PODCAST] step 1/5: fetching {} podcast feeds", feedSettings.feeds.size)
            val allEpisodes = mutableListOf<PodcastEpisode>()
            for (feed in feedSettings.feeds) {
                val fetched = fetcher.fetch(feed.url, username)
                log.info("[PODCAST]   feed {} fetched {} episodes", feed.url, fetched.size)
                allEpisodes.addAll(fetched)
            }

            // Dedup & filter existing
            val existing = episodesRepo.load(username)
            val existingGuids = existing.map { it.guid }.toHashSet()
            val newEpisodes = allEpisodes.filter { it.guid !in existingGuids }
                .distinctBy { it.guid }
            log.info("[PODCAST] {} new episodes to process", newEpisodes.size)

            // Save all new episodes in PENDING state
            for (episode in newEpisodes) {
                episodesRepo.upsert(username, episode.copy(status = "PENDING"))
            }

            // Process PENDING episodes through the pipeline
            processDownloading(username, categories)
            processTranscribing(username)
            processSummarizing(username, categories)

            meters.counter("newsfeed.podcasts.episodes.processed", "username", username)
                .increment(newEpisodes.size.toDouble())

            val took = Duration.between(started, Instant.now()).seconds.toInt()
            log.info("[PODCAST] done: {} new episodes in {}s", newEpisodes.size, took)
        } catch (e: Exception) {
            log.error("[PODCAST] refresh failed for '{}': {}", username, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }

    private fun processDownloading(username: String, categories: List<CategorySettings>) {
        val pending = episodesRepo.loadByStatus(username, "PENDING")
        if (pending.isEmpty()) return

        log.info("[PODCAST] step 2/5: downloading {} episodes", pending.size)
        for ((idx, episode) in pending.withIndex()) {
            try {
                log.info("[PODCAST]   download {}/{}: {}", idx + 1, pending.size, episode.title.take(60))
                episodesRepo.upsert(username, episode.copy(status = "DOWNLOADING"))

                // Download the audio file
                val audioBytes = downloadAudio(episode.podcastUrl!!, username)
                log.info("[PODCAST]   download {} KB", audioBytes.size / 1024)

                episodesRepo.upsert(
                    username,
                    episode.copy(
                        status = "TRANSCRIBING",
                        podcastUrl = episode.podcastUrl // Keep it for reference
                    )
                )
            } catch (e: Exception) {
                log.error("[PODCAST] download failed for {}: {}", episode.title, e.message)
                episodesRepo.upsert(
                    username,
                    episode.copy(
                        status = "DONE",
                        errorMessage = "download_failed: ${e.message?.take(100)}"
                    )
                )
            }
        }
    }

    private fun downloadAudio(url: String, username: String): ByteArray {
        val started = Instant.now()
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "PersonalNewsFeed/1.0")
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() >= 400) {
                throw Exception("http ${resp.statusCode()}")
            }
            resp.body()
        } finally {
            logCall(username, "podcast_download", url.take(120), started, "ok", null, 1)
        }
    }

    private fun processTranscribing(username: String) {
        val downloading = episodesRepo.loadByStatus(username, "TRANSCRIBING")
        if (downloading.isEmpty()) return

        log.info("[PODCAST] step 3/5: transcribing {} episodes", downloading.size)
        for ((idx, episode) in downloading.withIndex()) {
            try {
                log.info("[PODCAST]   transcribe {}/{}: {}", idx + 1, downloading.size, episode.title.take(60))

                // Download audio again (or we'd need to store it)
                val audioBytes = downloadAudio(episode.podcastUrl!!, username)

                // Call OpenAI Whisper API
                val transcript = transcribeAudio(audioBytes, episode.title, username)
                log.info("[PODCAST]   transcribed {} chars", transcript.length)

                episodesRepo.upsert(
                    username,
                    episode.copy(
                        status = "SUMMARIZING",
                        transcript = transcript
                    )
                )
            } catch (e: Exception) {
                log.warn("[PODCAST] transcribe failed for {}: {}", episode.title, e.message)
                // Fallback to show-notes
                episodesRepo.upsert(
                    username,
                    episode.copy(
                        status = "SUMMARIZING",
                        transcript = episode.showNotes, // Use show-notes as fallback
                        errorMessage = "transcribe_fallback: ${e.message?.take(100)}"
                    )
                )
            }
        }
    }

    private fun transcribeAudio(audioBytes: ByteArray, title: String, username: String): String {
        val started = Instant.now()
        return try {
            val apiKey = System.getenv("PNF_OPENAI_API_KEY")
                ?: throw Exception("PNF_OPENAI_API_KEY not set")

            // Use multipart form to upload audio
            val boundary = UUID.randomUUID().toString()
            val body = buildMultipartBody(audioBytes, boundary)

            val req = HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.openai.com/v1/audio/transcriptions"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()

            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() >= 400) {
                throw Exception("Whisper API error ${resp.statusCode()}: ${resp.body().take(200)}")
            }

            // Parse JSON response
            val tree = mapper.readTree(resp.body())
            val text = tree.path("text").asText("")
            logCall(username, "podcast_transcribe", title.take(100), started, "ok", null,
                (audioBytes.size / (128 * 1000)).toLong().coerceAtLeast(1))
            text
        } catch (e: Exception) {
            logCall(username, "podcast_transcribe", title.take(100), started, "error", e.message?.take(100), 0)
            throw e
        }
    }

    private fun processSummarizing(username: String, categories: List<CategorySettings>) {
        val summarizing = episodesRepo.loadByStatus(username, "SUMMARIZING")
        if (summarizing.isEmpty()) return

        log.info("[PODCAST] step 4/5: summarizing {} episodes", summarizing.size)

        for ((idx, episode) in summarizing.withIndex()) {
            try {
                log.info("[PODCAST]   summarize {}/{}: {}", idx + 1, summarizing.size, episode.title.take(60))

                // Summarize transcript or show-notes
                val textToSummarize = episode.transcript ?: episode.showNotes ?: ""
                val summary = summarizeTranscript(username, episode.title, textToSummarize, categories)

                // Generate FeedItem
                val feedItem = generateFeedItem(username, episode, summary, categories)
                feed.save(username, feedItem)

                episodesRepo.upsert(
                    username,
                    episode.copy(
                        status = "DONE",
                        feedItemId = feedItem.id,
                        completedAt = Instant.now()
                    )
                )
            } catch (e: Exception) {
                log.error("[PODCAST] summarize failed for {}: {}", episode.title, e.message, e)
                episodesRepo.upsert(
                    username,
                    episode.copy(
                        status = "DONE",
                        errorMessage = "summarize_failed: ${e.message?.take(100)}",
                        completedAt = Instant.now()
                    )
                )
            }
        }
    }

    private fun summarizeTranscript(
        username: String,
        title: String,
        transcript: String,
        categories: List<CategorySettings>
    ): String {
        val catList = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) " — ${c.extraInstructions.take(200)}" else ""
            "- ${c.id}: ${c.name}$instr"
        }

        val ai = anthropic.complete(
            operation = "summarizePodcastTranscript",
            action = "podcast_summarize",
            username = username,
            subject = title.take(120),
            model = anthropic.summaryModel(),
            maxTokens = 2000,
            system = "Je vat podcast-transcripts samen (200-300 woorden) in het Nederlands. Extraheer 3 hoofdclaims en eventueel 1 opvallend citaat.",
            user = """
                Beschikbare categorieën:
                $catList

                Podcast-titel: $title
                Transcript (eerste 5000 chars):
                ${transcript.take(5000)}

                Antwoord uitsluitend in geldig JSON:
                {"summary": "Nederlandse samenvatting 200-300 woorden", "category": "...", "topics": ["claim1", "claim2", "claim3"], "quote": "..."}
            """.trimIndent()
        )

        return try {
            val tree = mapper.readTree(extractJson(ai.text))
            tree.path("summary").asText("")
        } catch (e: Exception) {
            log.warn("[PODCAST] summary parse failed: {}", e.message)
            ai.text.take(1000)
        }
    }

    private fun generateFeedItem(
        username: String,
        episode: PodcastEpisode,
        summary: String,
        categories: List<CategorySettings>
    ): FeedItem {
        return FeedItem(
            id = UUID.randomUUID().toString(),
            title = episode.title,
            titleNl = episode.title,
            summary = summary,
            shortSummary = (episode.showNotes ?: episode.description).take(200),
            url = episode.podcastUrl,
            category = "podcast",
            source = episode.feedUrl,
            sourceRssIds = listOf(episode.guid),
            sourceUrls = listOf(episode.podcastUrl).filterNotNull(),
            topics = emptyList(),
            feedReason = "Podcast-aflevering samengeval",
            publishedDate = null,
            createdAt = Instant.now(),
            isSummary = true
        )
    }

    private fun buildMultipartBody(audioBytes: ByteArray, boundary: String): ByteArray {
        return buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.mp3\"\r\n")
            append("Content-Type: audio/mpeg\r\n\r\n")
        }.toByteArray() + audioBytes + "\r\n--$boundary\r\n".toByteArray() +
                buildString {
            append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            append("whisper-1\r\n")
            append("--$boundary--\r\n")
        }.toByteArray()
    }

    private fun logCall(
        username: String,
        action: String,
        subject: String,
        started: Instant,
        status: String,
        errorMessage: String?,
        units: Long
    ) {
        try {
            callLogger.log(
                ExternalCall(
                    id = UUID.randomUUID().toString(),
                    provider = if (action.contains("transcribe")) "openai" else "podcast_rss",
                    action = action,
                    username = username,
                    startTime = started,
                    endTime = Instant.now(),
                    durationMs = Instant.now().toEpochMilli() - started.toEpochMilli(),
                    units = units,
                    unitType = ExternalCall.UNIT_ITEMS,
                    costUsd = if (action.contains("transcribe")) (units * 0.006 / 60.0) else 0.0,
                    status = status,
                    errorMessage = errorMessage,
                    subject = subject
                )
            )
        } catch (e: Exception) {
            log.warn("[PODCAST] could not log external_call: {}", e.message)
        }
    }

    private fun extractJson(text: String): String {
        var s = text.trim()
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
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

data class PodcastRefreshRequested(val username: String)
