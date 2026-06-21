package com.vdzon.newsfeedbackend.rss

import com.rometools.rome.feed.synd.SyndEnclosureImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.vdzon.newsfeedbackend.rss.infrastructure.RssFetcher
import org.jdom2.Element
import com.vdzon.newsfeedbackend.external_call.ExternalCall
import com.vdzon.newsfeedbackend.external_call.ExternalCallLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RssFetcherImageUrlTest {

    private val fetcher = RssFetcher(object : ExternalCallLogger {
        override fun log(call: ExternalCall) {}
        override fun timed(
            provider: String, action: String, username: String, unitType: String,
            subject: String?, block: ExternalCallLogger.TimedContext.() -> Unit
        ) {}
    })

    @Test
    fun `extracts image from enclosure`() {
        val entry = SyndEntryImpl()
        val enc = SyndEnclosureImpl()
        enc.url = "https://example.com/image.jpg"
        enc.type = "image/jpeg"
        entry.enclosures = listOf(enc)

        assertEquals("https://example.com/image.jpg", fetcher.extractImageUrl(entry, ""))
    }

    @Test
    fun `extracts image from media-thumbnail foreignMarkup`() {
        val entry = SyndEntryImpl()
        val el = Element("thumbnail", "media", "http://search.yahoo.com/mrss/")
        el.setAttribute("url", "https://example.com/thumb.png")
        entry.foreignMarkup = listOf(el)

        assertEquals("https://example.com/thumb.png", fetcher.extractImageUrl(entry, ""))
    }

    @Test
    fun `extracts image from media-content foreignMarkup`() {
        val entry = SyndEntryImpl()
        val el = Element("content", "media", "http://search.yahoo.com/mrss/")
        el.setAttribute("url", "https://example.com/content.png")
        entry.foreignMarkup = listOf(el)

        assertEquals("https://example.com/content.png", fetcher.extractImageUrl(entry, ""))
    }

    @Test
    fun `extracts image from img tag in description`() {
        val entry = SyndEntryImpl()
        val html = """<p>tekst</p><img src="https://example.com/desc.jpg" alt="foto"/>"""

        assertEquals("https://example.com/desc.jpg", fetcher.extractImageUrl(entry, html))
    }

    @Test
    fun `returns null when no image present`() {
        val entry = SyndEntryImpl()
        assertNull(fetcher.extractImageUrl(entry, "<p>geen afbeelding hier</p>"))
    }

    @Test
    fun `enclosure wins over foreignMarkup`() {
        val entry = SyndEntryImpl()
        val enc = SyndEnclosureImpl()
        enc.url = "https://example.com/enclosure.jpg"
        enc.type = "image/jpeg"
        entry.enclosures = listOf(enc)
        val el = Element("thumbnail", "media", "http://search.yahoo.com/mrss/")
        el.setAttribute("url", "https://example.com/thumb.png")
        entry.foreignMarkup = listOf(el)

        assertEquals("https://example.com/enclosure.jpg", fetcher.extractImageUrl(entry, ""))
    }
}
