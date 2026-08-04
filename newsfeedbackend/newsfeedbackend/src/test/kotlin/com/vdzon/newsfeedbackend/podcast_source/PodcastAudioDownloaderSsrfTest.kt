package com.vdzon.newsfeedbackend.podcast_source

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastAudioDownloader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PodcastAudioDownloaderSsrfTest {

    private val loggedCalls = mutableListOf<ExternalCall>()

    // Alleen de logger meegeven: de default ssrfAllowLoopback = false levert het
    // productiegedrag op, zonder Spring-context.
    private val downloader = PodcastAudioDownloader(object : ExternalCallLogger {
        override fun log(call: ExternalCall) {
            loggedCalls += call
        }
    })

    @Test
    fun `blocks audio download for loopback host and returns null without sending a request`() {
        val url = "http://127.0.0.1:1/ep.mp3"

        val file = downloader.download("bob", "guid-1", url)

        assertNull(file)
        assertEquals(1, loggedCalls.size)
        val call = loggedCalls.single()
        assertEquals("error", call.status)
        assertTrue(call.errorMessage?.contains("geblokkeerd") ?: false)
        assertEquals(0L, call.units)
        // subject heeft hier de vorm "guid=… url=…"
        assertTrue(call.subject?.contains(url) ?: false)
    }

    @Test
    fun `blocks audio download for private rfc1918 host`() {
        val file = downloader.download("bob", "guid-2", "http://10.0.0.5/ep.mp3")

        assertNull(file)
        assertEquals(1, loggedCalls.size)
        val call = loggedCalls.single()
        assertEquals("error", call.status)
        assertEquals(0L, call.units)
        assertTrue(call.errorMessage?.contains("geblokkeerd") ?: false)
    }

    @Test
    fun `blocks audio download for non-http scheme`() {
        val file = downloader.download("bob", "guid-3", "file:///etc/passwd")

        assertNull(file)
        assertEquals(1, loggedCalls.size)
        val call = loggedCalls.single()
        assertEquals("error", call.status)
        assertEquals(0L, call.units)
        assertTrue(call.errorMessage?.contains("geblokkeerd") ?: false)
    }

    @Test
    fun `blocks audio download for link-local cloud metadata endpoint`() {
        val file = downloader.download("bob", "guid-4", "http://169.254.169.254/latest/meta-data/")

        assertNull(file)
        assertEquals(1, loggedCalls.size)
        val call = loggedCalls.single()
        assertEquals("error", call.status)
        assertEquals(0L, call.units)
        assertTrue(call.errorMessage?.contains("geblokkeerd") ?: false)
    }
}
