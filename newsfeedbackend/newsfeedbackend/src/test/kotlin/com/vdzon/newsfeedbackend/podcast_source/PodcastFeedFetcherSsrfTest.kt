package com.vdzon.newsfeedbackend.podcast_source

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastFeedFetcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PodcastFeedFetcherSsrfTest {

    private var loggedCall: ExternalCall? = null
    private val fetcher = PodcastFeedFetcher(object : ExternalCallLogger {
        override fun log(call: ExternalCall) {
            loggedCall = call
        }
    })

    @Test
    fun `blocks fetch for loopback host and returns error result without sending a request`() {
        val result = fetcher.fetch("http://127.0.0.1:1/feed.xml", username = "system")

        assertFalse(result.ok)
        assertTrue(result.errorMessage?.contains("geblokkeerd") ?: false)
        assertEquals("error", loggedCall?.status)
    }

    @Test
    fun `blocks fetch for private rfc1918 host`() {
        val result = fetcher.fetch("http://10.0.0.5/feed.xml", username = "system")

        assertFalse(result.ok)
        assertEquals("error", loggedCall?.status)
    }

    @Test
    fun `blocks fetch for non-http scheme`() {
        val result = fetcher.fetch("file:///etc/passwd", username = "system")

        assertFalse(result.ok)
        assertTrue(result.errorMessage?.contains("geblokkeerd") ?: false)
        assertEquals("error", loggedCall?.status)
    }
}
