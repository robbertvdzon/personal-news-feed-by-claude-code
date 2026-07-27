package com.vdzon.newsfeedbackend.podcast.api

import com.vdzon.newsfeedbackend.common.NotFoundException
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import com.vdzon.newsfeedbackend.podcast.EpisodeLookup
import com.vdzon.newsfeedbackend.podcast.PodcastTranslationService
import com.vdzon.newsfeedbackend.podcast.TranslationStart
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * KAN-63: REST-endpoints voor de "vertaal RSS-podcast-aflevering naar
 * NL-podcast"-flow.
 *
 * - `GET /api/podcast-source/by-rss-item/{rssItemId}` — wordt door de
 *   Flutter `RssPodcastDetailScreen` aangeroepen bij openen. Returnt de
 *   episode-guid (sleutel voor de translate-POST), transcript-lengte
 *   (input voor de client-side cost-schatting) en — als die bestaat —
 *   de bestaande vertaling met status. Daarmee weet de UI of 'ie
 *   "Vertaal & genereer" of "Bekijk vertaling" moet tonen.
 *
 * - `POST /api/podcast-source/{episodeGuid}/translate` — start de
 *   vertaling async. Returnt 202 + nieuwe podcast-id, of 200 + bestaande
 *   podcast-id als er al een (in progress of DONE) vertaling is
 *   (idempotency). 409 wanneer het transcript nog niet beschikbaar is.
 */
@RestController
@RequestMapping("/api/podcast-source")
class PodcastTranslationController(
    private val service: PodcastTranslationService
) {

    private fun user(): String = SecurityHelpers.currentUsername()

    @GetMapping("/by-rss-item/{rssItemId}")
    fun lookupByRssItem(@PathVariable rssItemId: String): EpisodeLookup =
        service.lookup(user(), rssItemId)
            ?: throw NotFoundException("podcast episode for rssItem $rssItemId")

    @PostMapping("/{episodeGuid}/translate")
    fun translate(@PathVariable episodeGuid: String): ResponseEntity<TranslationStart> {
        val result = service.startTranslation(user(), episodeGuid)
        val httpStatus = if (result.created) HttpStatus.ACCEPTED else HttpStatus.OK
        return ResponseEntity.status(httpStatus).body(result)
    }
}
