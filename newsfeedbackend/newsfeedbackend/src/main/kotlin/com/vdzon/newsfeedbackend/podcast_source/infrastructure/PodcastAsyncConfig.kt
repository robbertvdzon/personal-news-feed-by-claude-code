package com.vdzon.newsfeedbackend.podcast_source.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class PodcastAsyncConfig {

    @Bean(name = ["podcastTaskExecutor"])
    fun podcastTaskExecutor(
        @Value("\${app.podcast.ingestion-concurrency:1}") concurrency: Int
    ): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = concurrency
        executor.maxPoolSize = concurrency
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("podcast-processor-")
        executor.initialize()
        return executor
    }

    /**
     * SF-1739: aparte, expliciet single-threaded executor voor de
     * event-driven transcript-fase. Eén thread = max één aflevering
     * tegelijk in verwerking (de garantie die vroeger uit "max 1 episode
     * per scheduled tick" kwam). Bewust gescheiden van
     * `podcastTaskExecutor`, zodat een minutenlange Whisper-run de snelle
     * show-notes-cards niet ophoudt.
     */
    @Bean(name = ["podcastTranscriptExecutor"])
    fun podcastTranscriptExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 1
        executor.maxPoolSize = 1
        executor.queueCapacity = 200
        executor.setThreadNamePrefix("podcast-transcript-")
        executor.initialize()
        return executor
    }
}
