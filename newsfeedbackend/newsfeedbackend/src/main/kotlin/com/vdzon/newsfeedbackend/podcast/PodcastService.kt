package com.vdzon.newsfeedbackend.podcast

import java.time.Instant

interface PodcastService {
    fun list(username: String): List<Podcast>
    fun get(username: String, id: String): Podcast?
    fun create(username: String, dto: CreatePodcastDto): Podcast
    fun delete(username: String, id: String): Boolean
    fun audioBytes(username: String, id: String): ByteArray?
}

enum class PodcastStatus { PENDING, DETERMINING_TOPICS, GENERATING_SCRIPT, GENERATING_AUDIO, DONE, FAILED }
enum class TtsProvider { OPENAI, ELEVENLABS }

data class Podcast(
    val id: String,
    val title: String = "",
    val periodDescription: String = "",
    val periodDays: Int = 7,
    val durationMinutes: Int = 15,
    val status: PodcastStatus = PodcastStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val scriptText: String? = null,
    val topics: List<String> = emptyList(),
    val audioPath: String? = null,
    val durationSeconds: Int? = null,
    val customTopics: List<String> = emptyList(),
    val ttsProvider: TtsProvider = TtsProvider.OPENAI,
    val podcastNumber: Int = 0,
    val generationSeconds: Int? = null
)

data class CreatePodcastDto(
    val periodDays: Int = 7,
    val durationMinutes: Int = 15,
    val customTopics: List<String> = emptyList(),
    val ttsProvider: TtsProvider = TtsProvider.OPENAI
)
