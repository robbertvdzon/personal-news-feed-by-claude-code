package com.vdzon.newsfeedbackend.podcast_source.infrastructure

import com.vdzon.newsfeedbackend.podcast_source.domain.PodcastLongSummaryBackfiller
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * KAN-62 (AC #7): retroactieve verrijking van bestaande DONE-podcasts.
 *
 * De V9-migratie voegt `long_summary` + `key_takeaways` toe als
 * nullable kolommen; alle bestaande KAN-60-rijen krijgen daardoor
 * NULL. Deze runner pakt ze één-voor-één op (FIFO, oudste eerst), haalt
 * de bestaande `transcript` uit DB (geen nieuwe Whisper-download — die
 * is al gedraaid) en stuurt 'm opnieuw door
 * [com.vdzon.newsfeedbackend.podcast_source.domain.PodcastEpisodeSummarizer.summarize]
 * zodat de detail-velden alsnog gevuld worden.
 *
 * Draait éénmalig na ApplicationReady in een dedicated background-
 * thread. We gebruiken bewust **niet** [jakarta.annotation.PostConstruct]
 * + Spring's `@Async` — bij @Async op een @PostConstruct is de Spring-
 * proxy nog niet klaar en gaat de call synchroon (zoals KAN-58 ooit
 * heeft opgeleverd). [ApplicationReadyEvent] is na de hele bean-graph
 * geïnitialiseerd; van daaruit een eigen thread laat ons z'n duty-cycle
 * (sleep tussen calls) zelf besturen zonder Spring-Async-config.
 *
 * Tempo: 5 seconden pauze tussen Claude-calls (story-aanname). Voor 14
 * bestaande items × (~30s Claude-call + 5s sleep) ≈ 8 min total — ruim
 * binnen het "binnen 20-30 min na deploy"-window uit de story.
 */
@Component
class PodcastBackfillRunner(
    private val episodeRepo: PodcastEpisodeRepository,
    private val backfiller: PodcastLongSummaryBackfiller
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Wachttijd tussen Claude-calls. Conservatief gekozen om de
     * normale Claude-quota niet aan te tasten — als er nieuwe podcasts
     * binnenkomen tijdens de backfill mogen die niet wachten op een
     * burst van 14 historische calls.
     */
    private val pauseBetweenCalls = java.time.Duration.ofSeconds(5)

    @EventListener(ApplicationReadyEvent::class)
    fun runOnStartup() {
        val pending = try {
            episodeRepo.findDoneNeedingLongSummary()
        } catch (e: Exception) {
            // Niet fataal voor de rest van de app — de migratie kan nog
            // niet gedraaid hebben op een dev-machine, of de DB hapert.
            log.warn("[PodcastBackfill] kon DONE-rijen niet ophalen: {}", e.message)
            return
        }
        if (pending.isEmpty()) {
            log.info("[PodcastBackfill] geen DONE-rijen zonder longSummary — backfill niet nodig")
            return
        }
        log.info("[PodcastBackfill] {} DONE-podcasts vinden geen longSummary — start retroactieve verrijking",
            pending.size)

        // Daemon-thread zodat een lopende backfill een graceful shutdown
        // van de app niet blokkeert. Single-thread executor want we
        // willen seriële afhandeling (story AC: niet alles tegelijk).
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "podcast-backfill").apply { isDaemon = true }
        }
        executor.submit { processBatch(pending.map { it.username to it.guid }) }
        executor.shutdown()
    }

    private fun processBatch(items: List<Pair<String, String>>) {
        var ok = 0
        var failed = 0
        for ((idx, ref) in items.withIndex()) {
            val (username, guid) = ref
            try {
                // Lees opnieuw uit DB — gebruikersinteractie of een
                // parallelle transcript-rerun kan tussendoor de rij
                // hebben aangepast.
                val ep = episodeRepo.get(username, guid)
                if (ep == null) {
                    log.warn("[PodcastBackfill] guid={} verdwenen uit DB tijdens backfill", guid)
                    failed++
                    continue
                }
                if (!ep.longSummary.isNullOrBlank()) {
                    log.debug("[PodcastBackfill] guid={} ondertussen al gevuld — overgeslagen", guid)
                    ok++
                    continue
                }
                val success = backfiller.backfillLongSummary(ep)
                if (success) ok++ else failed++
            } catch (e: Exception) {
                log.warn("[PodcastBackfill] onverwachte fout op guid={}: {}", guid, e.message)
                failed++
            }
            // Pauze tussen calls — ook na de laatste niet nodig, maar
            // skip alleen als we klaar zijn met de hele lijst.
            if (idx < items.size - 1) {
                try {
                    TimeUnit.MILLISECONDS.sleep(pauseBetweenCalls.toMillis())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.info("[PodcastBackfill] onderbroken — gestopt na {} verwerkte rijen", idx + 1)
                    return
                }
            }
        }
        log.info("[PodcastBackfill] klaar: ok={}, failed={}, totaal={}", ok, failed, items.size)
    }
}
