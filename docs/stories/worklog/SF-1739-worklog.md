# SF-1739 - Worklog

Story-context bij eerste pickup:
Event-driven transcriptverwerking, uurlijkse recovery, Hikari scale-to-zero, Neon-script en docs

Implementeer alles in één ontwikkelstap, inclusief tests.

1) Event-driven start: publiceer bij de overgang naar NEEDS_TRANSCRIPT in PodcastShowNotesProcessor (rond regel 87, ná het opslaan van de episode) een nieuw applicatie-event (username + guid) in een publiek package van de podcast_source-module (Spring Modulith-grens; ModuleStructureTest moet groen blijven). Voeg een @EventListener @Async-consument toe volgens het bestaande patroon (RssRefreshPipeline, EventDiscoveryPipeline) die PodcastTranscriptProcessor.processTranscript(...) aanroept en bij RateLimited dezelfde backoff schrijft als nu (retry_count++, next_attempt_at = now + 5m/15m/45m/24h via processor.nextRetryDelay). Garandeer maximaal één aflevering tegelijk in verwerking via in-proces serialisatie (lock/semafoor) plus de bestaande status-/next_attempt_at-conditie, zodat dubbele events niet tot dubbele verwerking leiden.

2) Recovery-job: verwijder de @Scheduled(fixedDelay 120000, initialDelay 60000) uit PodcastTranscriptWorker.kt:52-54 en de properties app.podcast.transcript-worker.interval-ms en initial-delay-ms (application.properties:107-108). Vervang door één @Scheduled(cron = ...) die hoogstens elk uur draait, met @SchedulerLock conform RssScheduler. De job pakt op: (a) afleveringen op NEEDS_TRANSCRIPT met verlopen next_attempt_at via findOneReadyForTranscript (gemist door restart of retry-klaar), en (b) de show-notes-timeout-promotie via findShowNotesExpiredForPromotion + markFeedPromotionAttempted VÓÓR publish van PodcastPromotionRequested (anti-loop-fix behouden). promotion-timeout-hours blijft bestaan. De job is vangnet, niet de normale route. Optioneel toegestaan: een eenmalige in-memory hertrigger op next_attempt_at via Spring TaskScheduler zodat een 5-minutenretry ook echt na ~5 minuten start; wordt die niet gebouwd, dan is een retry tot een uur later acceptabel - documenteer de keuze.

3) Scale-to-zero niet blokkeren: zet spring.datasource.hikari.minimum-idle=0 en een idle-timeout ruim onder 300s (nu ontbreekt minimum-idle, waardoor minimumIdle==maximumPoolSize=5 en idle-timeout=600000 feitelijk inactief is). Geen keepaliveTime, geen validatie-query op timer, geen @Scheduled die vaker dan elk uur de db raakt. Voeg geen management.endpoint.health.group.*-config toe; probes blijven op de standaard Spring readiness/liveness-groepen zonder db-indicator.

4) Neon: voeg een idempotent deploy/neon-endpoint-config.sh toe dat via de Neon REST API de read/write-endpoint zet op suspend_timeout_seconds=300, autoscaling_limit_min_cu=0.25, autoscaling_limit_max_cu=1, en daarna de effectieve waarden read-only teruglaat en print. Volg het env-var-/API-patroon van deploy/preview-ns-labeller/labeller.sh (NEON_API, NEON_API_KEY, NEON_PROJECT_ID). Lees credentials uitsluitend uit env, log geen sleutels/tokens, commit geen credentials. Uitvoeren tegen productie is een operatorstap - het script hoeft in de factory niet gedraaid te worden.

5) Documentatie: runbook-sectie over de nieuwe flow (event-start + uurlijkse recovery), het draaien én read-only verifiëren van het script, verwacht cold-startgedrag na suspend en terugdraaien naar de oude waarden. Werk teksten bij die de twee-minuten-worker beschrijven (o.a. specs/backend-functional-spec.md, docs/onboarding-senior-developer.md).

6) Tests (hoort bij deze subtaak): dek retry-backoff (5m/15m/45m/24h met retry_count/next_attempt_at), show-notes-timeout-promotie incl. de feed_promotion_attempted_at-marker (geen herhaalde AI-calls), maximaal één aflevering tegelijk, idempotentie bij dubbele events, en dat de recovery-job een door restart gemiste aflevering alsnog oppakt. Pas bestaande e2e-tests (o.a. PodcastIngestE2eTest) aan die op de 2-minutentick leunen. Sluit af met een eigen reviewronde en zorg dat `mvn -B clean verify` in newsfeedbackend/newsfeedbackend groen is. Geen Flyway-migratie: het schema blijft ongewijzigd.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Resultaat developer-run (SF-1740)

Volledige uitwerking en onderbouwing: `docs/stories/SF-1739-neon-kosten-event-driven-podcastworker.md`.

Kort:
- Nieuw event `PodcastTranscriptRequested` (podcast_source, moduleniveau) + `@EventListener @Async`-consument
  `PodcastTranscriptPipeline` op een single-threaded `podcastTranscriptExecutor` met proceswijde lock.
- `PodcastTranscriptWorker` (2-minuten-tick) verwijderd; vervangen door `PodcastRecoveryScheduler`
  (`@Scheduled(cron)` uurlijks + `@SchedulerLock`, publiceert events voor achterstallige afleveringen en
  doet de show-notes-timeout-promotie). Properties `interval-ms`/`initial-delay-ms` weg,
  `app.podcast.recovery.cron` erbij (`-` = uit, gebruikt in de e2e-suite).
- Bewuste keuze: geen in-memory hertrigger op `next_attempt_at`; een retry start op z'n laatst bij de
  eerstvolgende uurlijkse run (story staat dit expliciet toe).
- Hikari: `minimum-idle=0` + `idle-timeout=60000` (onder de suspend-timeout van 300s), geen keepalive.
- Probes ongewijzigd gelaten: `/actuator/health/{readiness,liveness}` zonder db-indicator, geen
  `management.endpoint.health.group.*` toegevoegd.
- `deploy/neon-endpoint-config.sh`: idempotent, credentials alleen uit env, `--verify` is read-only.
  Uitvoeren tegen productie is een operatorstap (geen Neon-credentials in de factory).
- Docs: runbook §6.1 (nieuw), `specs/backend-functional-spec.md` §6.4 + §9-tabel, `specs/frontend-spec.md`,
  `docs/onboarding-senior-developer.md`, `deploy/README.md`.

Vangnet: `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend` → **BUILD SUCCESS**,
100 unit-tests + 67 e2e-tests, 0 failures, 0 errors (3:28 min).
