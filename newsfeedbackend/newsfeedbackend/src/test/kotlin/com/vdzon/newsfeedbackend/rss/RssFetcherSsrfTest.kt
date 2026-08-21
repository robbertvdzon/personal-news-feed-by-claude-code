package com.vdzon.newsfeedbackend.rss

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.rss.infrastructure.RssFetcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RssFetcherSsrfTest {

    private var loggedCall: ExternalCall? = null
    private val fetcher = RssFetcher(object : ExternalCallLogger {
        override fun log(call: ExternalCall) {
            loggedCall = call
        }
    })

    @Test
    fun `blocks fetch for loopback host and returns empty list without sending a request`() {
        val items = fetcher.fetch("http://127.0.0.1:1/feed.xml", username = "system")

        assertTrue(items.isEmpty())
        assertEquals("error", loggedCall?.status)
        assertTrue(loggedCall?.errorMessage?.contains("geblokkeerd") ?: false)
    }

    @Test
    fun `blocks fetch for private rfc1918 host`() {
        val items = fetcher.fetch("http://10.0.0.5/feed.xml", username = "system")

        assertTrue(items.isEmpty())
        assertEquals("error", loggedCall?.status)
    }

    @Test
    fun `blocks fetch for non-http scheme`() {
        val items = fetcher.fetch("file:///etc/passwd", username = "system")

        assertTrue(items.isEmpty())
        assertEquals("error", loggedCall?.status)
        assertTrue(loggedCall?.errorMessage?.contains("geblokkeerd") ?: false)
    }
}
