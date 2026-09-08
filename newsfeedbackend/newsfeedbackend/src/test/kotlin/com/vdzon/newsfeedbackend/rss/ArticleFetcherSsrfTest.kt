package com.vdzon.newsfeedbackend.rss

import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import com.vdzon.newsfeedbackend.rss.infrastructure.ArticleFetcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArticleFetcherSsrfTest {

    private val loggedCalls = mutableListOf<ExternalCall>()

    // Alleen de logger meegeven: de default ssrfAllowLoopback = false levert het
    // productiegedrag op, zonder Spring-context.
    private val fetcher = ArticleFetcher(object : ExternalCallLogger {
        override fun log(call: ExternalCall) {
            loggedCalls += call
        }
    })

    @Test
    fun `blocks article fetch for loopback host and returns null without sending a request`() {
        val url = "http://127.0.0.1:1/artikel.html"

        val text = fetcher.fetchPlainText("bob", url)

        assertNull(text)
        assertEquals(1, loggedCalls.size)
        val call = loggedCalls.single()
        assertEquals("error", call.status)
        assertTrue(call.errorMessage?.contains("geblokkeerd") ?: false)
        assertEquals(0L, call.units)
        assertEquals(url, call.subject)
    }

    // De errorMessage-assertie in de drie tests hieronder bewaakt dat de afwijzing
    // van `SsrfUrlValidator` komt en niet van elders; ruim hem dus niet los op,
    // alleen in dezelfde diff die `ArticleFetcher` meeverandert. Twee motieven, niet door
    // elkaar halen: bij het niet-http-schema bewaakt hij dat `ArticleFetcher` valideert
    // VOOR het opbouwen van het `HttpRequest` — draait iemand die volgorde om, dan weigert
    // `HttpRequest.Builder.uri(...)` de URL zelf en staat er "invalid URI scheme file" in
    // external_calls in plaats van onze eigen reden. Bij de RFC1918- en link-local-case
    // weigert de JDK niets; daar bewaakt hij dat de "error"-status van onze validator komt
    // en niet van een netwerk- of DNS-fout.
    @Test
    fun `blocks article fetch for private rfc1918 host`() {
        val text = fetcher.fetchPlainText("bob", "http://10.0.0.5/artikel.html")

        assertNull(text)
        assertEquals(1, loggedCalls.size)
        assertEquals("error", loggedCalls.single().status)
        assertTrue(loggedCalls.single().errorMessage?.contains("geblokkeerd") ?: false)
    }

    @Test
    fun `blocks article fetch for non-http scheme`() {
        val text = fetcher.fetchPlainText("bob", "file:///etc/passwd")

        assertNull(text)
        assertEquals(1, loggedCalls.size)
        assertEquals("error", loggedCalls.single().status)
        assertTrue(loggedCalls.single().errorMessage?.contains("geblokkeerd") ?: false)
    }

    @Test
    fun `blocks article fetch for link-local cloud metadata endpoint`() {
        val text = fetcher.fetchPlainText("bob", "http://169.254.169.254/latest/meta-data/")

        assertNull(text)
        assertEquals(1, loggedCalls.size)
        assertEquals("error", loggedCalls.single().status)
        assertTrue(loggedCalls.single().errorMessage?.contains("geblokkeerd") ?: false)
    }
}
