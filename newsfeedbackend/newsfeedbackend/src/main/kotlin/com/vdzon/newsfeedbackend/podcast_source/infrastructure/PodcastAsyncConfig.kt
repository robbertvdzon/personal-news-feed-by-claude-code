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
}
