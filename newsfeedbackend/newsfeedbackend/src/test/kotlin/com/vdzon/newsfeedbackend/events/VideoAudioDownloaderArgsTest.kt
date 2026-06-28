package com.vdzon.newsfeedbackend.events

import com.vdzon.newsfeedbackend.events.infrastructure.VideoAudioDownloader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SF-580 (security-pass): verifieert dat de yt-dlp-argumentenlijst de
 * `--` end-of-options-separator vlak vóór de video-URL plaatst. Dat
 * voorkomt argument-injectie wanneer een (uit externe AI/Tavily-output
 * afkomstige) URL met `-` begint en anders als yt-dlp-vlag zou worden
 * geïnterpreteerd. Voor geldige http(s)-URL's blijft het gedrag gelijk.
 */
class VideoAudioDownloaderArgsTest {

    @Test
    fun `url staat als laatste argument achter de -- separator`() {
        val args = VideoAudioDownloader.buildArgs("yt-dlp", "/tmp/out", "https://youtu.be/abc")

        assertEquals("https://youtu.be/abc", args.last())
        val sepIdx = args.indexOf("--")
        assertTrue(sepIdx >= 0, "verwacht een `--` separator in de argumentenlijst")
        assertEquals(sepIdx + 1, args.lastIndex, "`--` moet direct vóór de URL staan")
    }

    @Test
    fun `een url die met streepje begint wordt niet als optie geparsed`() {
        // Zonder `--` zou yt-dlp dit als (onbekende) optie zien.
        val malicious = "--exec=rm -rf /"
        val args = VideoAudioDownloader.buildArgs("yt-dlp", "/tmp/out", malicious)

        assertEquals(malicious, args.last())
        // Alles na `--` is positioneel: de injectie-poging staat erachter.
        assertTrue(args.indexOf("--") < args.indexOf(malicious))
    }

    @Test
    fun `binary en output-template blijven ongewijzigd aanwezig`() {
        val args = VideoAudioDownloader.buildArgs("/usr/bin/yt-dlp", "/tmp/base", "https://example.com/v")

        assertEquals("/usr/bin/yt-dlp", args.first())
        assertTrue(args.contains("/tmp/base.%(ext)s"))
        assertTrue(args.containsAll(listOf("--extract-audio", "--audio-format", "mp3")))
    }
}
