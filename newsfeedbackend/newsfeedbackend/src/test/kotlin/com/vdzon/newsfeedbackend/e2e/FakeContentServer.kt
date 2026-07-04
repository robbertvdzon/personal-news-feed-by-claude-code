package com.vdzon.newsfeedbackend.e2e

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded HTTP-server die RSS-feeds, podcast-feeds en artikel-HTML
 * serveert. Feed-URL's zijn in deze app user-configuratie, dus e2e-tests
 * registreren gewoon een `http://localhost:<port>/...`-URL als feed —
 * er is geen productie-seam nodig om de fetchers te onderscheppen.
 */
class FakeContentServer {

    private val responses = ConcurrentHashMap<String, Pair<String, ByteArray>>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val entry = responses[exchange.requestURI.path]
            if (entry == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                val (contentType, body) = entry
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        start()
    }

    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    fun serve(path: String, contentType: String, body: String) {
        responses[path] = contentType to body.toByteArray(Charsets.UTF_8)
    }

    /** Binaire response (bv. dummy MP3-bytes voor de TTS-endpoints). */
    fun serveBytes(path: String, contentType: String, body: ByteArray) {
        responses[path] = contentType to body
    }

    fun serveArticle(path: String, title: String, bodyText: String) =
        serve(path, "text/html", "<html><head><title>$title</title></head><body><p>$bodyText</p></body></html>")

    fun reset() = responses.clear()

    /** RSS 2.0-feed met artikel-items; pubDate = nu, dus binnen het 4-dagen-window van de RssFetcher. */
    fun rssFeedXml(feedTitle: String, items: List<RssTestItem>): String {
        val itemXml = items.joinToString("\n") { item ->
            """
            <item>
              <title>${item.title}</title>
              <link>${item.url}</link>
              <guid>${item.url}</guid>
              <description>${item.description}</description>
              <pubDate>${rfc1123(item.publishedAt)}</pubDate>
            </item>
            """.trimIndent()
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>$feedTitle</title>
              <link>${url("/")}</link>
              <description>e2e test feed</description>
              $itemXml
            </channel></rss>
        """.trimIndent().trim()
    }

    /** Podcast-RSS met enclosure + guid + itunes:duration, zoals PodcastFeedFetcher verwacht. */
    fun podcastFeedXml(podcastName: String, episodes: List<PodcastTestEpisode>): String {
        val itemXml = episodes.joinToString("\n") { ep ->
            """
            <item>
              <title>${ep.title}</title>
              <guid>${ep.guid}</guid>
              <description>${ep.showNotes}</description>
              <pubDate>${rfc1123(ep.publishedAt)}</pubDate>
              <enclosure url="${ep.audioUrl}" length="1024" type="audio/mpeg"/>
              <itunes:duration>${ep.durationSeconds / 60}:${"%02d".format(ep.durationSeconds % 60)}</itunes:duration>
            </item>
            """.trimIndent()
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
              <title>$podcastName</title>
              <link>${url("/")}</link>
              <description>e2e test podcast</description>
              $itemXml
            </channel></rss>
        """.trimIndent().trim()
    }

    // ---- tavily helpers ------------------------------------------------
    // De TavilyClient POST't naar <base-url>/search en <base-url>/extract;
    // in e2e-tests wijst app.tavily.base-url naar deze server (prefix
    // "/tavily"), dus tests serveren op "/tavily/search" en
    // "/tavily/extract". Het JSON-formaat volgt wat TavilyClient parseert:
    // search  -> {"results":[{"title","url","content","published_date"}]}
    // extract -> {"results":[{"url","raw_content"}]}

    /** Response-body voor POST /search in het formaat dat [com.vdzon.newsfeedbackend.search.TavilyClient.search] verwacht. */
    fun tavilySearchJson(results: List<TavilyTestSearchResult>): String {
        val items = results.joinToString(",") { r ->
            buildString {
                append("""{"title":"${jsonEsc(r.title)}","url":"${jsonEsc(r.url)}","content":"${jsonEsc(r.content)}"""")
                r.publishedDate?.let { append(""","published_date":"${jsonEsc(it)}"""") }
                append("}")
            }
        }
        return """{"results":[$items]}"""
    }

    /** Response-body voor POST /extract in het formaat dat [com.vdzon.newsfeedbackend.search.TavilyClient.extract] verwacht. */
    fun tavilyExtractJson(pages: Map<String, String>): String {
        val items = pages.entries.joinToString(",") { (url, text) ->
            """{"url":"${jsonEsc(url)}","raw_content":"${jsonEsc(text)}"}"""
        }
        return """{"results":[$items]}"""
    }

    private fun jsonEsc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    data class TavilyTestSearchResult(
        val title: String,
        val url: String,
        val content: String = "Snippet uit het zoekresultaat",
        val publishedDate: String? = null
    )

    private fun rfc1123(instant: Instant): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(instant)

    data class RssTestItem(
        val title: String,
        val url: String,
        val description: String = "Test artikel beschrijving",
        val publishedAt: Instant = Instant.now()
    )

    data class PodcastTestEpisode(
        val title: String,
        val guid: String,
        val audioUrl: String,
        val showNotes: String = "Show notes van de testaflevering",
        val durationSeconds: Int = 1800,
        val publishedAt: Instant = Instant.now()
    )
}
