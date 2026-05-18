package com.vdzon.newsfeedbackend.podcast_source.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdzon.newsfeedbackend.ai.AnthropicClient
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisode
import com.vdzon.newsfeedbackend.podcast_source.PodcastEpisodeStatus
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.AudioTranscoder
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastAudioDownloader
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.PodcastEpisodeRepository
import com.vdzon.newsfeedbackend.podcast_source.infrastructure.WhisperClient
import com.vdzon.newsfeedbackend.rss.RssItem
import com.vdzon.newsfeedbackend.rss.domain.PodcastPromotionRequested
import com.vdzon.newsfeedbackend.rss.infrastructure.RssItemRepository
import com.vdzon.newsfeedbackend.settings.SettingsService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * KAN-60: tweefasen-pipeline-stappen voor één podcast-aflevering.
 *
 * **Fase 1 — snelle show-notes-card** ([processShowNotes], async via
 * `podcastTaskExecutor`):
 *   PENDING → SUMMARIZING_FROM_NOTES → (NEEDS_TRANSCRIPT óf
 *   SHOW_NOTES_DONE als transcribeEnabled=false)
 *
 *   Geen audio-download, geen Whisper-call. Claude vat de RSS-
 *   `<description>` samen, het kaartje verschijnt direct in de RSS-tab
 *   met `summary_source='show_notes'` (voorlopige badge in de UI).
 *
 * **Fase 2 — async transcript** ([processTranscript], door
 * [PodcastTranscriptWorker] aangeroepen):
 *   NEEDS_TRANSCRIPT → DOWNLOADING → TRANSCRIBING → SUMMARIZING → DONE
 *
 *   Download MP3, Whisper, herberekening Claude. Overschrijft de summary
 *   op het rss_items-kaartje en zet `summary_source='transcript'`
 *   (badge verdwijnt). Trigget daarna de feed-promotie.
 *
 * **Foutbeleid**:
 *   - Whisper 429/5xx → episode blijft NEEDS_TRANSCRIPT, retry_count++,
 *     next_attempt_at = now + backoff (5m/15m/45m/24h). Geen FAILED.
 *   - Whisper fatale fout / geen API-key → episode wordt SHOW_NOTES_DONE
 *     (terminale "we proberen het niet meer"-state — badge blijft).
 *   - Onverwachte exception in show-notes-fase → FAILED, geen card.
 */
@Component
class PodcastEpisodeProcessor(
    private val episodeRepo: PodcastEpisodeRepository,
    private val rssRepo: RssItemRepository,
    private val downloader: PodcastAudioDownloader,
    private val transcoder: AudioTranscoder,
    private val whisper: WhisperClient,
    private val anthropic: AnthropicClient,
    private val settings: SettingsService,
    private val mapper: ObjectMapper,
    private val events: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Whisper's harde limit is 26.214.400 bytes (25 MiB). We compress
         * naar deze drempel zodat we ruim onder de limit blijven (1 MiB
         * marge dekt eventuele afronding/header-overhead).
         */
        private const val MAX_WHISPER_BYTES = 25L * 1024 * 1024 - 1024 * 1024  // = 24 MiB

        /**
         * KAN-62: max aantal transcript-chars dat we naar Claude sturen.
         * 80k is genoeg voor ~70-min podcasts (Latent Space, Lex Fridman)
         * en past ruim binnen Sonnet's 200k context-window. Bestond
         * eerder als 12k char-afkap — dat was te krap voor de lange
         * samenvatting (90-min podcasts ≈ 60-90k chars). Show-notes-
         * input is bijna altijd <5k dus dezelfde limiet werkt voor
         * beide fasen zonder aparte logica.
         */
        internal const val MAX_CLAUDE_INPUT_CHARS = 80_000
    }

    /**
     * Fase 1 — show-notes-summary. Wordt vanuit
     * [PodcastIngestionPipeline] gekickt direct na het ontdekken van een
     * nieuwe aflevering, zodat de card binnen seconden in de RSS-tab
     * verschijnt (AC #1).
     */
    @Async("podcastTaskExecutor")
    fun processShowNotes(username: String, guid: String, transcribeEnabled: Boolean) {
        MDC.put("username", username)
        try {
            val initial = episodeRepo.get(username, guid) ?: run {
                log.warn("[PodcastEpisode] verdween uit DB voor we 'm konden verwerken — guid={}", guid)
                return
            }
            // Idempotency: alleen in PENDING-staat verwerken; bij een refresh
            // die op een al-verwerkte episode landt (b.v. SUMMARIZING_FROM_NOTES
            // door een vorige tick) niet opnieuw doen.
            if (initial.status != PodcastEpisodeStatus.PENDING) {
                log.debug("[PodcastEpisode] guid={} status={} — skip show-notes-fase", guid, initial.status)
                return
            }
            log.info("[PodcastEpisode] show-notes-fase start guid={} title='{}'",
                guid, initial.title.take(80))

            var ep = save(initial.copy(status = PodcastEpisodeStatus.SUMMARIZING_FROM_NOTES))

            if (ep.showNotes.isBlank()) {
                log.warn("[PodcastEpisode] geen show-notes — guid={} (kan geen voorlopige card maken)", guid)
                save(ep.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Geen show-notes om een voorlopige samenvatting van te maken"
                ))
                return
            }

            val summarized = summarize(username, ep, ep.showNotes)
            if (summarized == null) {
                save(ep.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Claude-samenvatting op show-notes faalde"
                ))
                return
            }

            val rssItemId = ep.rssItemId ?: UUID.randomUUID().toString()
            val rss = buildRssItem(ep, summarized, rssItemId, summarySource = "show_notes")
            rssRepo.upsert(username, rss)

            // Bij transcribeEnabled=false: dit is meteen de eindstaat. Card
            // krijgt permanent een show-notes-badge (refiner-aanname), en
            // we promoten 'm direct (geen wachten op transcript dat nooit
            // komt — voorkomt dat een card 24h "vast" zit voordat de
            // timeout-promotie 'm oppakt).
            val nextStatus = if (transcribeEnabled) {
                PodcastEpisodeStatus.NEEDS_TRANSCRIPT
            } else {
                PodcastEpisodeStatus.SHOW_NOTES_DONE
            }
            ep = save(ep.copy(
                status = nextStatus,
                rssItemId = rssItemId,
                summary = summarized.shortSummary,
                // KAN-62: vul de detail-velden vanaf show-notes-fase zodat
                // het detail-scherm al iets te tonen heeft voordat het
                // transcript binnen is. Bij de latere transcript-fase
                // worden ze met inhoudelijk rijkere versies overschreven.
                longSummary = summarized.longSummary.ifBlank { null },
                keyTakeaways = summarized.keyTakeaways,
                summarySource = "show_notes",
                errorMessage = null
            ))

            if (!transcribeEnabled) {
                triggerFeedPromotion(username, rssItemId)
            }
            log.info("[PodcastEpisode] show-notes-fase klaar guid={} → status={} rssItemId={}",
                guid, nextStatus, rssItemId)
        } catch (e: Exception) {
            log.error("[PodcastEpisode] onverwachte fout in show-notes-fase guid={}: {}", guid, e.message, e)
            episodeRepo.get(username, guid)?.let {
                episodeRepo.upsert(it.copy(
                    status = PodcastEpisodeStatus.FAILED,
                    errorMessage = "Onverwachte fout: ${e.message ?: e.javaClass.simpleName}"
                ))
            }
        } finally {
            MDC.clear()
        }
    }

    /**
     * Fase 2 — transcript-fase. Wordt synchroon aangeroepen vanuit
     * [PodcastTranscriptWorker] (de scheduled tick pakt MAX 1 episode op).
     * Retourneert hoe het is afgelopen zodat de worker bij rate-limit de
     * backoff kan instellen.
     */
    fun processTranscript(username: String, guid: String): TranscriptResult {
        MDC.put("username", username)
        var audioFile: java.io.File? = null
        // Aparte var voor 't (mogelijk gecomprimeerde) bestand dat naar
        // Whisper gaat. Kan dezelfde zijn als audioFile bij kleine episodes,
        // of een ge-transcodeerde temp-file bij grote (>24 MiB).
        var transcodedForWhisper: AudioTranscoder.TranscodeResult? = null
        return try {
            val initial = episodeRepo.get(username, guid)
                ?: return TranscriptResult.Skipped("episode verdwenen uit DB").also {
                    log.warn("[PodcastEpisode] transcript-fase: guid={} niet in DB", guid)
                }
            if (initial.status != PodcastEpisodeStatus.NEEDS_TRANSCRIPT) {
                log.debug("[PodcastEpisode] transcript-fase: guid={} status={} — skip", guid, initial.status)
                return TranscriptResult.Skipped("status=${initial.status}")
            }
            log.info("[PodcastEpisode] transcript-fase start guid={} title='{}' (retry_count={})",
                guid, initial.title.take(80), initial.retryCount)

            var ep = save(initial.copy(status = PodcastEpisodeStatus.DOWNLOADING))
            audioFile = downloader.download(username, guid, ep.audioUrl)
            if (audioFile == null) {
                log.warn("[PodcastEpisode] download faalde voor guid={} url={}", guid, ep.audioUrl)
                // Download-fouten zijn meestal niet-herstelbaar (404, dode
                // CDN-link); we laten de show-notes-card staan i.p.v. een
                // retry-storm te bouwen.
                save(ep.copy(
                    status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                    errorMessage = "Audio-download faalde — card blijft op show-notes"
                ))
                return TranscriptResult.Fatal("audio-download faalde")
            }

            ep = save(ep.copy(status = PodcastEpisodeStatus.TRANSCRIBING))
            // KAN-60-followup: Whisper accepteert max 25 MiB. ThoughtWorks-
            // afleveringen zaten net boven die limit en faalden met HTTP 413.
            // We comprimeren pre-upload naar mono 32 kbps MP3 wanneer 't
            // origineel te groot is. Bij ffmpeg-fout valt de transcoder
            // terug op het originele bestand zodat Whisper z'n eigen 413
            // teruggeeft en de bestaande SHOW_NOTES_DONE-fallback werkt.
            transcodedForWhisper = transcoder.ensureBelowSize(audioFile, MAX_WHISPER_BYTES)
            val outcome = whisper.transcribe(
                username = username,
                episodeGuid = guid,
                audioFile = transcodedForWhisper.file,
                audioFilename = guessFilename(ep.audioUrl),
                audioDurationSec = (ep.durationSeconds ?: 0).toLong()
            )
            when (outcome) {
                is WhisperClient.TranscribeOutcome.RateLimited -> {
                    // Niet FAILED — episode blijft in de retry-pool. De
                    // worker zet next_attempt_at + retry_count zelf na de
                    // return-value; hier zetten we 'm alleen terug op
                    // NEEDS_TRANSCRIPT en sturen de status-code mee.
                    save(ep.copy(
                        status = PodcastEpisodeStatus.NEEDS_TRANSCRIPT,
                        errorMessage = "Whisper rate-limited: ${outcome.message.take(160)}"
                    ))
                    log.warn("[PodcastEpisode] Whisper rate-limited guid={} ({}). Retry volgt via worker.",
                        guid, outcome.statusCode)
                    return TranscriptResult.RateLimited(outcome.statusCode)
                }
                is WhisperClient.TranscribeOutcome.NoApiKey,
                is WhisperClient.TranscribeOutcome.FatalError -> {
                    val msg = (outcome as? WhisperClient.TranscribeOutcome.FatalError)?.message
                        ?: "Whisper: geen API-key geconfigureerd"
                    // Geen Whisper-resultaat te krijgen; we stoppen met
                    // proberen en laten de show-notes-card permanent staan.
                    save(ep.copy(
                        status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                        errorMessage = "Whisper fataal: ${msg.take(160)}"
                    ))
                    log.warn("[PodcastEpisode] Whisper fatale fout guid={}: {} — card blijft op show-notes",
                        guid, msg)
                    return TranscriptResult.Fatal(msg)
                }
                is WhisperClient.TranscribeOutcome.Success -> {
                    val transcript = outcome.text
                    if (transcript.isBlank()) {
                        log.warn("[PodcastEpisode] Whisper gaf leeg transcript voor guid={}", guid)
                        save(ep.copy(
                            status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                            errorMessage = "Whisper gaf een leeg transcript terug"
                        ))
                        return TranscriptResult.Fatal("empty transcript")
                    }
                    ep = save(ep.copy(
                        status = PodcastEpisodeStatus.SUMMARIZING,
                        transcript = transcript
                    ))
                    val summarized = summarize(username, ep, transcript)
                    if (summarized == null) {
                        // Claude faalde op het transcript — terugvallen op
                        // de bestaande show-notes-summary. Card blijft
                        // intact, badge ook (summarySource blijft show_notes).
                        save(ep.copy(
                            status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                            errorMessage = "Claude-samenvatting op transcript faalde"
                        ))
                        log.warn("[PodcastEpisode] Claude faalde op transcript guid={} — card blijft op show-notes", guid)
                        return TranscriptResult.Fatal("claude summarize failed")
                    }

                    val rssItemId = ep.rssItemId ?: UUID.randomUUID().toString()
                    val rss = buildRssItem(ep, summarized, rssItemId, summarySource = "transcript")
                    rssRepo.upsert(username, rss)

                    save(ep.copy(
                        status = PodcastEpisodeStatus.DONE,
                        rssItemId = rssItemId,
                        summary = summarized.shortSummary,
                        // KAN-62: transcript-based long-summary +
                        // takeaways overschrijven de eerder uit show-notes
                        // gegenereerde versie. Beide nu inhoudelijk juist
                        // omdat we tot 80k chars transcript meesturen.
                        longSummary = summarized.longSummary.ifBlank { null },
                        keyTakeaways = summarized.keyTakeaways,
                        summarySource = "transcript",
                        nextAttemptAt = null,
                        errorMessage = null
                    ))
                    log.info("[PodcastEpisode] DONE (transcript-based) guid={} → rss_items.id={}",
                        guid, rssItemId)
                    triggerFeedPromotion(username, rssItemId)
                    return TranscriptResult.Success
                }
            }
        } catch (e: Exception) {
            log.error("[PodcastEpisode] onverwachte fout in transcript-fase guid={}: {}",
                guid, e.message, e)
            episodeRepo.get(username, guid)?.let {
                episodeRepo.upsert(it.copy(
                    status = PodcastEpisodeStatus.SHOW_NOTES_DONE,
                    errorMessage = "Onverwachte fout in transcript-fase: ${e.message ?: e.javaClass.simpleName}"
                ))
            }
            TranscriptResult.Fatal(e.message ?: e.javaClass.simpleName)
        } finally {
            // Ge-transcodeerde tussenfile opruimen als 't een aparte temp
            // was (anders is 'ie hetzelfde object als audioFile, één delete
            // is genoeg). Beide afzonderlijk in try/catch zodat een fout op
            // de ene de andere niet blokkeert.
            try {
                transcodedForWhisper?.takeIf { it.isTemporary }?.file?.delete()
            } catch (e: Exception) {
                log.warn("[PodcastEpisode] kon transcoded temp-file niet verwijderen: {}", e.message)
            }
            try {
                audioFile?.delete()
            } catch (e: Exception) {
                log.warn("[PodcastEpisode] kon temp-file niet verwijderen: {}", e.message)
            }
            MDC.clear()
        }
    }

    /**
     * KAN-62: retroactief de longSummary + keyTakeaways voor één
     * bestaande DONE-aflevering vullen. Gebruikt het al opgeslagen
     * `transcript` als input — geen Whisper-call. Update zowel de
     * podcast_episodes-rij als de gekoppelde rss_items-rij zodat het
     * detail-scherm de nieuwe velden direct ziet.
     *
     * Retourneert true als de backfill geslaagd is (of niets te doen),
     * false bij een Claude-fout (zodat de runner z'n teller kan loggen
     * en eventueel later opnieuw kan proberen).
     */
    fun backfillLongSummary(ep: PodcastEpisode): Boolean {
        if (ep.transcript.isBlank()) {
            log.debug("[PodcastEpisode-backfill] guid={} heeft geen transcript — overgeslagen", ep.guid)
            return true
        }
        if (!ep.longSummary.isNullOrBlank()) {
            log.debug("[PodcastEpisode-backfill] guid={} heeft al een longSummary — overgeslagen", ep.guid)
            return true
        }
        return try {
            MDC.put("username", ep.username)
            val summarized = summarize(ep.username, ep, ep.transcript)
            if (summarized == null) {
                log.warn("[PodcastEpisode-backfill] Claude faalde op transcript voor guid={} — laat rij ongewijzigd",
                    ep.guid)
                return false
            }

            val rssItemId = ep.rssItemId
            if (rssItemId != null) {
                val existing = rssRepo.load(ep.username).find { it.id == rssItemId }
                if (existing != null) {
                    rssRepo.upsert(ep.username, existing.copy(
                        // shortSummary blijft staan om scroll-flicker te
                        // voorkomen (de oude is meestal vergelijkbaar). De
                        // toegevoegde long-velden zijn waar de story om
                        // vraagt.
                        longSummary = summarized.longSummary.ifBlank { null },
                        keyTakeaways = summarized.keyTakeaways,
                        // Topics + category eventueel ook bijwerken — de
                        // nieuwe prompt geeft consistentere topics.
                        topics = summarized.topics.ifEmpty { existing.topics },
                        category = summarized.category.ifBlank { existing.category }
                    ))
                } else {
                    log.warn("[PodcastEpisode-backfill] rss_items.id={} ontbreekt voor guid={} — alleen podcast_episodes geüpdatet",
                        rssItemId, ep.guid)
                }
            }

            save(ep.copy(
                longSummary = summarized.longSummary.ifBlank { null },
                keyTakeaways = summarized.keyTakeaways
            ))
            log.info("[PodcastEpisode-backfill] guid={} verrijkt: longSummary={} chars, {} takeaways",
                ep.guid, summarized.longSummary.length, summarized.keyTakeaways.size)
            true
        } catch (e: Exception) {
            log.warn("[PodcastEpisode-backfill] onverwachte fout voor guid={}: {}", ep.guid, e.message)
            false
        } finally {
            MDC.clear()
        }
    }

    /**
     * Backoff-tabel uit de story (AC #4): 5m → 15m → 45m → 24h. retryCount
     * is het aantal mislukte pogingen vóór deze tick; retryCount=0 ná de
     * eerste 429 → wachttijd 5m.
     */
    fun nextRetryDelay(retryCount: Int): Duration = when (retryCount) {
        0 -> Duration.ofMinutes(5)
        1 -> Duration.ofMinutes(15)
        2 -> Duration.ofMinutes(45)
        else -> Duration.ofHours(24)
    }

    private fun triggerFeedPromotion(username: String, rssItemId: String) {
        try {
            events.publishEvent(PodcastPromotionRequested(username = username, rssItemId = rssItemId))
        } catch (e: Exception) {
            log.warn("[PodcastEpisode] kon promotion-event niet publiceren voor rssItemId={}: {}",
                rssItemId, e.message)
        }
    }

    private fun save(ep: PodcastEpisode): PodcastEpisode = episodeRepo.upsert(ep)

    internal data class Summarized(
        val shortSummary: String,
        val category: String,
        val topics: List<String>,
        /**
         * KAN-62: 400-600 woorden NL-prose in 3-5 alinea's voor het
         * detail-scherm. Blank-string acceptabel (b.v. zeer korte
         * show-notes-input) — frontend valt dan terug op shortSummary.
         */
        val longSummary: String,
        /**
         * KAN-62: 5-10 bullets van max 1 regel. Lege lijst acceptabel
         * voor heel korte input.
         */
        val keyTakeaways: List<String>
    )

    /**
     * Eén Claude-call die zowel de korte card-samenvatting als de
     * uitgebreidere detail-velden (longSummary + keyTakeaways) levert.
     * Eén round-trip i.p.v. twee om de latency op het kritieke pad
     * (show-notes-fase) niet te verdubbelen.
     */
    internal fun summarize(username: String, ep: PodcastEpisode, input: String): Summarized? {
        val categories = settings.getCategories(username).filter { it.enabled || it.isSystem }
        val catList = categories.joinToString("\n") { c ->
            val instr = if (c.extraInstructions.isNotBlank()) " — ${c.extraInstructions.take(200)}" else ""
            "- ${c.id}: ${c.name}$instr"
        }
        // KAN-62: Whisper-transcripts van 60-90 min lopen tot ~70k chars.
        // We sturen alles tot 80k naar Claude zodat de lange samenvatting
        // het inhoudelijk verloop van de aflevering reflecteert i.p.v.
        // alleen de opening (zoals de oude 12k afkap). Bij langere
        // transcripts kappen we eerlijk af; 80k past ruim binnen de
        // 200k context van Sonnet en haalt nog steeds 99% van de
        // beschikbare podcasts compleet binnen.
        val sample = if (input.length > MAX_CLAUDE_INPUT_CHARS) {
            input.take(MAX_CLAUDE_INPUT_CHARS) + "\n[...afgekort wegens lengte...]"
        } else {
            input
        }
        val ai = anthropic.complete(
            operation = "summarizePodcastEpisode",
            action = com.vdzon.newsfeedbackend.external_call.ExternalCall.ACTION_PODCAST_EPISODE_SUMMARIZE,
            username = username,
            subject = "Podcast '${ep.podcastName.take(40)}' — ${ep.title.take(80)}",
            model = anthropic.summaryModel(),
            // Verhoogd van 4096: longSummary (~800 tokens) + takeaways
            // (~300) + shortSummary/topics/category laten anders nauwelijks
            // ruimte over en Claude knipt 'm midden in een zin af.
            maxTokens = 4096,
            system = """
                Je vat podcast-afleveringen samen in het Nederlands.

                shortSummary: 1-2 zinnen (~30-50 woorden, plain text — geen markdown) die in 1 oogopslag duidelijk maken waar deze aflevering over gaat. Eindig met een punt.

                longSummary: 3-5 alinea's plain-text Nederlands (~400-600 woorden) die gestructureerd beschrijven wat in de aflevering wordt besproken — chronologisch of thematisch. Reflecteer concrete inhoud uit het transcript (namen van tools/frameworks/personen, citaten, voorbeelden) i.p.v. marketing-platitudes. Scheid alinea's met een lege regel. Géén markdown-headers, géén bullet-list — gewone prose. Bij korte input (b.v. show-notes van <500 woorden) mag het korter — 2-3 alinea's volstaat dan, geen opgeklopte vulling.

                keyTakeaways: 5-10 concrete takeaways/inzichten als JSON-array van strings. Eén bullet per regel, max ~20 woorden, géén sub-bullets, géén markdown-headers. Voor tech-podcasts: noem tools/concepts/frameworks in de bullet zelf. Bij hele korte input mag de lijst korter (3-4 takeaways). Niet alleen "ze bespraken X" — schrijf wat erover gezegd is.

                topics: 3-8 korte Nederlandse onderwerpen die in de aflevering aan bod zijn gekomen. Pak concrete inhoudelijke topics, geen marketing-woorden.

                category: kies één id uit de gebruikersvoorkeuren hieronder (fallback "overig").

                Antwoord uitsluitend met geldig JSON, geen markdown-codefences (geen ```), geen prose ervoor of erna.
            """.trimIndent(),
            user = buildString {
                appendLine("Podcast: ${ep.podcastName}")
                appendLine("Aflevering: ${ep.title}")
                if (!ep.publishedDate.isNullOrBlank()) appendLine("Datum: ${ep.publishedDate}")
                if ((ep.durationSeconds ?: 0) > 0) appendLine("Duur: ${(ep.durationSeconds ?: 0) / 60} min")
                appendLine()
                appendLine("Beschikbare categorieën (id, naam, voorkeur):")
                appendLine(catList)
                appendLine()
                appendLine("Input (transcript of show-notes; mogelijk afgekapt):")
                appendLine(sample)
                appendLine()
                appendLine("Antwoord met JSON in dit schema:")
                append("""{"shortSummary": "...", "longSummary": "...", "keyTakeaways": ["...", "..."], "topics": ["..."], "category": "kotlin"}""")
            }
        )
        val raw = ai.text.trim()
        if (raw.isBlank()) {
            log.warn("[PodcastEpisode] Claude gaf lege response voor guid={}", ep.guid)
            return null
        }
        return try {
            val tree = mapper.readTree(extractJson(raw))
            val shortSum = tree.path("shortSummary").asText("").trim()
            val cat = tree.path("category").asText("overig").ifBlank { "overig" }
            val topics = tree.path("topics").mapNotNull { it.asText().takeUnless { t -> t.isBlank() } }
            val longSum = tree.path("longSummary").asText("").trim()
            val takeaways = tree.path("keyTakeaways")
                .mapNotNull { it.asText().takeUnless { t -> t.isBlank() } }
                .map { it.trim() }
            if (shortSum.isBlank()) {
                log.warn("[PodcastEpisode] Claude gaf geen shortSummary voor guid={}", ep.guid)
                return null
            }
            Summarized(
                shortSummary = shortSum,
                category = cat,
                topics = topics,
                longSummary = longSum,
                keyTakeaways = takeaways
            )
        } catch (e: Exception) {
            log.warn("[PodcastEpisode] parse-fout in Claude-response voor guid={}: {} — head: {}",
                ep.guid, e.message, raw.take(300))
            null
        }
    }

    private fun buildRssItem(
        ep: PodcastEpisode,
        sum: Summarized,
        rssItemId: String,
        summarySource: String
    ): RssItem {
        // Bij re-runs hergebruiken we het oude rss_items.id zodat
        // gebruikersinteractie (sterren, isRead) bewaard blijft.
        val existing = ep.rssItemId?.let { rid ->
            rssRepo.load(ep.username).find { it.id == rid }
        }
        return (existing ?: RssItem(
            id = rssItemId,
            title = ep.title,
            url = ep.audioUrl.ifBlank { ep.feedUrl },
            feedUrl = ep.feedUrl,
            source = ep.podcastName,
            timestamp = Instant.now(),
            mediaType = "PODCAST",
            audioUrl = ep.audioUrl,
            durationSeconds = ep.durationSeconds,
            summarySource = summarySource
        )).copy(
            title = ep.title,
            summary = sum.shortSummary,
            url = ep.audioUrl.ifBlank { ep.feedUrl },
            feedUrl = ep.feedUrl,
            source = ep.podcastName,
            snippet = ep.showNotes.take(1000),
            publishedDate = ep.publishedDate,
            category = sum.category,
            topics = sum.topics,
            mediaType = "PODCAST",
            audioUrl = ep.audioUrl,
            durationSeconds = ep.durationSeconds,
            summarySource = summarySource,
            // KAN-62: schrijf longSummary alleen als Claude er één gaf —
            // anders blijft een eerdere waarde staan (b.v. wanneer de
            // transcript-fase mislukt en we terugvallen op de oude
            // show-notes-card).
            longSummary = sum.longSummary.ifBlank { null },
            keyTakeaways = sum.keyTakeaways,
            processedAt = Instant.now()
        )
    }

    private fun guessFilename(audioUrl: String): String {
        val path = audioUrl.substringBefore('?').substringAfterLast('/')
        return if (path.isBlank() || !path.contains('.')) "audio.mp3" else path
    }

    /**
     * Pakt het eerste balanced JSON-object uit een tekst. Claude wikkelt
     * regelmatig JSON in ```json ... ``` of zet er prose voor. We
     * doen hier exact dezelfde truc als in [com.vdzon.newsfeedbackend.rss.domain.RssRefreshPipeline].
     */
    private fun extractJson(text: String): String {
        var s = text.trim()
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.dropLast(3).trim()
        val curly = s.indexOf('{')
        val bracket = s.indexOf('[')
        val start = when {
            curly < 0 && bracket < 0 -> return s
            curly < 0 -> bracket
            bracket < 0 -> curly
            else -> minOf(curly, bracket)
        }
        val openChar = s[start]
        val closeChar = if (openChar == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (inString) {
                if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return s.substring(start)
    }

    sealed class TranscriptResult {
        object Success : TranscriptResult()
        data class RateLimited(val statusCode: Int) : TranscriptResult()
        data class Fatal(val message: String) : TranscriptResult()
        data class Skipped(val reason: String) : TranscriptResult()
    }
}
