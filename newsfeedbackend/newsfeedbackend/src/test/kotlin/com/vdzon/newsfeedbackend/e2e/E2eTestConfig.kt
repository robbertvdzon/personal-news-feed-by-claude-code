package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.ai.OpenAiChatClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files

/**
 * Testconfiguratie voor de e2e-suite (zelfde opzet als de
 * softwarefactory-repo): de hele Spring-app draait echt, inclusief
 * Postgres (Testcontainers) en Flyway-migraties. Alleen de externe
 * dependencies zijn vervangen:
 *
 *  - OpenAI (chat/JSON): [FakeOpenAiChatClient] als @Primary-bean.
 *  - RSS-/podcast-feeds en artikelen: [FakeContentServer] — feeds zijn
 *    user-config, dus tests registreren gewoon een localhost-URL.
 *
 * De container en fakes zijn statisch: één per test-JVM (failsafe forkt
 * per testklasse, dus elke klasse krijgt een verse omgeving).
 */
@TestConfiguration
class E2eTestConfig {

    companion object {
        val POSTGRES: PostgreSQLContainer<*> by lazy {
            PostgreSQLContainer("postgres:16-alpine").apply { start() }
        }
        val OPENAI = FakeOpenAiChatClient()
        val CONTENT = FakeContentServer()
        val DATA_DIR: String by lazy {
            Files.createTempDirectory("pnf-e2e-data").toAbsolutePath().toString()
        }
    }

    @Bean
    @Primary
    fun fakeOpenAiChatClient(): OpenAiChatClient = OPENAI
}
