package com.vdzon.newsfeedbackend.events.domain

import com.vdzon.newsfeedbackend.events.infrastructure.EventRepository
import com.vdzon.newsfeedbackend.settings.SettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * KAN-65 + KAN-68: ontdekt per gebruiker grote tech-events met Tavily
 * web-search + Claude.
 *
 * KAN-68 verandert de seed-strategie:
 *  - Primaire bron: de per-user lijst event-voorkeuren (vrije namen,
 *    bv. "JavaOne", "KotlinConf"). Per naam doen we één gerichte
 *    Tavily-search + één Claude-extract.
 *  - Secundaire bron: de bestaande categorie-settings (KAN-65 gedrag).
 *  - Ná de seed-pass één extra "similar"-Claude-call die op basis van de
 *    voorkeuren-lijst soortgelijke events binnen dezelfde scene/community
 *    voorstelt. Cap: 1 extra call per run per user.
 *  - Events zonder valide start_date krijgen één extra Tavily-lookup;
 *    levert die nog steeds niets op dan wordt het event verworpen.
 *  - De denylist filtert weg: een eerder verwijderd event wordt niet
 *    opnieuw aangemaakt.
 *
 * Deze class is de dunne orkestrator: run-loop met per-user lock + MDC
 * en de volgorde van de passes. Het echte werk is opgeknipt naar
 * [EventExtractor] (Tavily + AI-extractie), [EventDateEnricher]
 * (datum-verrijking/-validatie), [EventPersister] (dedup, denylist,
 * opslag) en [EventFeedAnnouncer] (aankondigings-feed-item).
 *
 * Per-user ReentrantLock + Spring @EventListener/@Async — identiek
 * patroon als [com.vdzon.newsfeedbackend.rss.domain.RssRefreshPipeline].
 */
@Component
class EventDiscoveryPipeline(
    private val repo: EventRepository,
    private val settings: SettingsService,
    private val extractor: EventExtractor,
    private val persister: EventPersister,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Max aantal voorkeuren dat we per run als seed gebruiken. Boven
     * deze cap kappen we af zodat één gebruiker met een rare lange
     * lijst (50+ namen) niet ineens 50× Tavily belt.
     */
    private val maxSeedQueries = 20

    @EventListener
    @Async
    fun onDiscover(event: EventDiscoveryRequested) = run(event.username)

    fun run(username: String) {
        val lock = locks.computeIfAbsent(username) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("[Events] discovery already running for '{}'", username)
            return
        }
        MDC.put("username", username)
        try {
            val started = Instant.now()
            log.info("[Events] start event-discovery voor '{}'", username)

            val preferences = settings.getEventPreferences(username).names.take(maxSeedQueries)
            val denylist = settings.getEventDenylist(username).entries.map { it.normalizedId }.toHashSet()
            val cats = settings.getCategories(username).filter { it.enabled && !it.isSystem }

            if (preferences.isEmpty() && cats.isEmpty()) {
                log.info("[Events] geen voorkeuren én geen categorieën voor '{}' — niets te zoeken", username)
                return
            }

            val existing = repo.load(username)
            var newCount = 0
            var updatedCount = 0
            var rejectedNoDate = 0
            var rejectedDenylisted = 0

            fun tally(outcome: EventPersister.PersistOutcome) {
                newCount += outcome.created
                updatedCount += outcome.updated
                rejectedNoDate += outcome.rejectedNoDate
                rejectedDenylisted += outcome.rejectedDenylisted
            }

            // ── 1. Per voorkeur (PRIMAIRE seed) ──────────────────────
            for (pref in preferences) {
                val discovered = extractor.discoverForSeed(username, pref)
                if (discovered.isEmpty()) continue
                tally(persister.persistDiscovered(username, discovered, existing, denylist))
            }

            // ── 2. Eén "similar"-call op basis van de voorkeuren ────
            if (preferences.isNotEmpty()) {
                val similar = extractor.discoverSimilar(username, preferences)
                if (similar.isNotEmpty()) {
                    log.info("[Events] similar-call gaf {} kandidaten", similar.size)
                    tally(persister.persistDiscovered(username, similar, existing, denylist))
                }
            }

            // ── 3. Secundair: categorie-gebaseerde discovery (KAN-65) ─
            for (cat in cats) {
                val discovered = extractor.discoverForCategory(username, cat)
                if (discovered.isEmpty()) continue
                tally(persister.persistDiscovered(username, discovered, existing, denylist))
            }

            meters.counter("newsfeed.events.discovered", "username", username).increment(newCount.toDouble())
            meters.timer("newsfeed.events.discovery.duration", "username", username)
                .record(Duration.between(started, Instant.now()))
            val took = Duration.between(started, Instant.now()).seconds.toInt()
            log.info(
                "[Events] klaar voor '{}': {} nieuw, {} bijgewerkt, {} verworpen (no-date), {} overgeslagen (denylist), duur {}s",
                username, newCount, updatedCount, rejectedNoDate, rejectedDenylisted, took
            )
        } catch (e: Exception) {
            log.error("[Events] discovery mislukt voor '{}': {}", username, e.message, e)
        } finally {
            MDC.clear()
            lock.unlock()
        }
    }
}
