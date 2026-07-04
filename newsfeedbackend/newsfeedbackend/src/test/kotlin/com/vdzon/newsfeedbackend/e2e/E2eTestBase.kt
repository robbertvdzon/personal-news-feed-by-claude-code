package com.vdzon.newsfeedbackend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Basisklasse voor alle e2e-tests: start de volledige app op een random
 * poort tegen een echte Postgres (Testcontainers); alleen externe
 * dependencies zijn gefaked (zie [E2eTestConfig]). HTTP-calls gaan via
 * een kale [java.net.http.HttpClient] — bewust geen framework-client,
 * zodat de tests precies doen wat de Flutter-app ook doet.
 *
 * Test-isolatie komt van unieke usernames per test ([uniqueUsername]) —
 * alle data in deze app is per-user, dus tests zien elkaars rijen niet
 * en er is geen table-truncate nodig.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eTestConfig::class)
abstract class E2eTestBase {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { E2eTestConfig.POSTGRES.jdbcUrl }
            registry.add("spring.datasource.username") { E2eTestConfig.POSTGRES.username }
            registry.add("spring.datasource.password") { E2eTestConfig.POSTGRES.password }
            registry.add("app.data-dir") { E2eTestConfig.DATA_DIR }
            // Transcript-worker niet laten ticken tijdens tests: de
            // transcript-fase (Whisper) hoort expliciet gescript te zijn.
            registry.add("app.podcast.transcript-worker.initial-delay-ms") { "3600000" }
            // Base-URL's van externe services naar de fake-server zodat een
            // gemiste seam nooit het echte internet raakt.
            registry.add("app.openai.base-url") { E2eTestConfig.CONTENT.url("/openai") }
            registry.add("app.tavily.base-url") { E2eTestConfig.CONTENT.url("/tavily") }
            registry.add("app.elevenlabs.base-url") { E2eTestConfig.CONTENT.url("/elevenlabs") }
        }
    }

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var mapper: ObjectMapper

    private val http: HttpClient = HttpClient.newHttpClient()

    protected val openAi get() = E2eTestConfig.OPENAI
    protected val content get() = E2eTestConfig.CONTENT

    @BeforeEach
    fun resetFakes() {
        openAi.reset()
        content.reset()
    }

    // ---- http helpers -------------------------------------------------

    data class HttpResult(val status: Int, val body: String) {
        fun json(mapper: ObjectMapper): JsonNode = mapper.readTree(body.ifBlank { "null" })
    }

    protected fun request(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null
    ): HttpResult {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(15))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        )
        val resp: HttpResponse<String> = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return HttpResult(resp.statusCode(), resp.body())
    }

    protected fun get(path: String, token: String? = null): HttpResult = request("GET", path, null, token)
    protected fun post(path: String, token: String? = null, body: String = "{}"): HttpResult =
        request("POST", path, body, token)
    protected fun put(path: String, token: String? = null, body: String = "{}"): HttpResult =
        request("PUT", path, body, token)
    protected fun delete(path: String, token: String? = null): HttpResult = request("DELETE", path, null, token)

    protected fun getJson(path: String, token: String? = null): JsonNode = get(path, token).json(mapper)

    // ---- auth helpers -------------------------------------------------

    data class TestUser(val username: String, val password: String, val token: String)

    protected fun uniqueUsername(prefix: String = "user"): String =
        "$prefix-${UUID.randomUUID().toString().take(8)}"

    protected fun registerUser(prefix: String = "user"): TestUser {
        val username = uniqueUsername(prefix)
        val password = "geheim123"
        val resp = post("/api/auth/register", body = """{"username": "$username", "password": "$password"}""")
        check(resp.status == 201) { "register faalde: ${resp.status} ${resp.body}" }
        val token = resp.json(mapper).path("token").asText()
        check(token.isNotBlank()) { "register gaf geen token: ${resp.body}" }
        return TestUser(username, password, token)
    }

    // ---- async helpers ------------------------------------------------

    /** Wacht (max [timeoutSeconds]s) tot [condition] true geeft — voor de @Async pipelines. */
    protected fun await(timeoutSeconds: Long = 30, condition: () -> Boolean) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(timeoutSeconds))
            .pollInterval(Duration.ofMillis(250))
            .until { condition() }
    }
}
