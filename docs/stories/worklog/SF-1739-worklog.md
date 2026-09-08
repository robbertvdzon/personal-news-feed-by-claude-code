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

## Review SF-1740 (reviewer)

Beoordeeld: volledige story-diff `git diff main...HEAD` (25 bestanden) tegen de story-AC's,
`docs/factory/technical-spec.md` en de bestaande repo-conventies. **Akkoord, geen blockers.**

Geverifieerd per AC:
- AC1/2: event wordt gepubliceerd ná `save(...)` in `PodcastShowNotesProcessor.kt:118-120`, alleen
  bij `nextStatus == NEEDS_TRANSCRIPT`. Grep bevestigt dat er nog exact twee plekken zijn die
  `NEEDS_TRANSCRIPT` zetten (show-notes-fase → event; rate-limit-pad in `PodcastTranscriptProcessor`
  → recovery-job). `@Scheduled` in `src/main`: alleen RssScheduler (uurlijks/dagelijks), twee weekly
  event-jobs en `PodcastRecoveryScheduler` (cron, uurlijks). `transcript-worker.interval-ms` en
  `initial-delay-ms` komen nergens in code/config meer voor.
- AC3: `@Scheduled(cron = ${app.podcast.recovery.cron:0 5 * * * *})` + `@SchedulerLock` conform
  `RssScheduler`; e2e `de recovery-job pakt een door restart gemiste aflevering alsnog op` dekt het
  restart-scenario.
- AC4: `scheduleBackoff` is 1-op-1 overgenomen uit de verwijderde `PodcastTranscriptWorker`
  (retry_count++, `nextRetryDelay(retryCount)`, re-load vóór upsert); marker-vóór-publish
  (anti-loop) idem. Tests dekken de hele tabel (0/1/2/3/9), de wachtkamer, dubbele events,
  max-1-tegelijk (concurrency-test met 4 threads) en de marker-volgorde via `inOrder`.
- AC5: `minimum-idle=0` + `idle-timeout=60000` (< 300s), geen keepalive/validatie-timer.
- AC6: `deploy/base/backend-deployment.yaml:95-105` ongewijzigd op de standaardgroepen; geen
  `management.endpoint.health.group.*` in de diff.
- AC7: `deploy/neon-endpoint-config.sh` — mode 100755, `bash -n` groen, credentials alleen uit env,
  Authorization-header nooit gelogd, `--verify` doet uitsluitend GET's, patch alleen bij afwijking
  (numerieke `jq -e`-vergelijking, dus 1 vs 1.0 telt niet als drift).
- AC8/9: runbook §6.1 dekt instellingen, draaien, read-only verifiëren, cold start en terugdraaien;
  specs/onboarding bijgewerkt. Geen endpoint-wijziging → `specs/openapi.yaml` terecht ongemoeid
  (de "~2 min" op regel 257 gaat over fase 1, niet over de verwijderde tick). Geen Flyway-migratie,
  schema ongewijzigd. Modulith-grens ok: event staat op moduleniveau (`podcast_source/`), net als
  `rss/RssEvents.kt`.
- AC10: vangnetbewijs = developer-run `mvn -B clean verify` BUILD SUCCESS (100 unit + 67 e2e,
  0 failures/errors) op deze revisie; niet opnieuw gedraaid conform reviewer-instructie.

Niet-blokkerende opmerkingen:
- [suggestie] `PodcastEpisodeRepository.findOneReadyForTranscript` heeft na deze wijziging geen
  enkele caller meer (alleen nog een KDoc-verwijzing). De story vroeg 'm te behouden, dus laten
  staan is verdedigbaar; overweeg 'm in een opruimstory te verwijderen of expliciet als
  "bewust behouden, ongebruikt" te markeren.
- [suggestie] De e2e roept `recovery.recover()` via de ShedLock-proxy aan met
  `lockAtLeastFor = "1m"`. Zou een toekomstige test binnen dezelfde minuut nogmaals `recover()`
  aanroepen, dan slaat ShedLock die stil over en faalt de test raadselachtig. Vermeld dat in een
  comment als er tests bijkomen.
- [info] Bewuste keuze "geen in-memory hertrigger" (retry start uiterlijk bij de eerstvolgende
  uurlijkse run) staat conform de story gedocumenteerd in KDoc, spec, story-log en worklog.
- [info] `MAX_EPISODES_PER_RUN = 10` betekent dat een achterstand van >10 afleveringen met ~10 per
  uur leegloopt; staat gedocumenteerd in de spec en is met een event-driven happy path onwaarschijnlijk.

## SF-1741 — Tester (story-brede test)

Omgeving: preview `https://pnf-pr-199.vdzonsoftware.nl` (namespace `pnf-pr-199`),
backend-image `sha-ab5ace9`. De reviewer-commit `a66905c` is worklog-only, dus de
preview draait de volledige code van deze story.

Uitgevoerde verificatie:
- **Unit-/componenttests**: `mvn -B --no-transfer-progress clean test` in
  `newsfeedbackend/newsfeedbackend` → BUILD SUCCESS, exitcode 0, **100/100 groen**,
  0 failures/errors. Nieuw en groen: `PodcastTranscriptPipelineTest` (8) en
  `PodcastRecoverySchedulerTest` (8). De Testcontainers-e2e (`PodcastIngestE2eTest`
  incl. de 2 nieuwe SF-1739-tests) draait in het volledige harness-vangnet
  (`mvn clean verify`); op deze tester-runner ontbreekt Docker (`docker: command not found`),
  daarom hier alleen de surefire-suite gedraaid — het vangnet zelf draait de harness.
- **AC1/AC3 (event-driven + recovery)**: `PodcastShowNotesProcessor` publiceert
  `PodcastTranscriptRequested` bij de overgang naar `NEEDS_TRANSCRIPT`;
  `PodcastTranscriptPipeline` (`@EventListener @Async("podcastTranscriptExecutor")`,
  corePool=maxPool=1 + `ReentrantLock`) verwerkt serieel, herleest de status en
  skipt bij status≠NEEDS_TRANSCRIPT of lopende backoff (idempotentie).
  `PodcastRecoveryScheduler` staat op `@Scheduled(cron=0 5 * * * *)` +
  `@SchedulerLock(podcastRecovery)` en hertriggert via hetzelfde event.
- **AC2 (geen poll meer)**: `PodcastTranscriptWorker.kt` is verwijderd; grep over
  `src/main` geeft nog exact 4 `@Scheduled`-annotaties (RSS hourly + daily 06:00,
  2× weekly events) plus de nieuwe uurlijkse recovery — niets vaker dan 1×/uur.
  `app.podcast.transcript-worker.interval-ms`/`initial-delay-ms` komen nergens meer
  voor; alleen `promotion-timeout-hours` blijft (bewust).
- **AC5 live bewezen**: `/actuator/prometheus` op de preview toont
  `hikaricp_connections_min{pool="HikariPool-1"} 0.0` (en max 5.0), dus
  `minimum-idle=0` is daadwerkelijk actief; `idle-timeout=60000` (<300s), geen
  keepalive/validatie-timer in de properties.
- **AC6 live bewezen**: `/actuator/health/readiness` en `/actuator/health/liveness`
  geven beide alleen `{"status":"UP"}` zonder db-component (de db-indicator zit
  alleen in de ongegroepeerde `/actuator/health`); geen
  `management.endpoint.health.group.*` in de config.
- **AC7**: `deploy/neon-endpoint-config.sh` is executable, `bash -n` groen, leest
  `NEON_API_KEY`/`NEON_PROJECT_ID` uitsluitend uit env, logt geen headers/tokens,
  patcht alleen bij afwijking (idempotent) en leest de effectieve waarden terug;
  `--verify` doet uitsluitend GET's.
- **AC8**: runbook §6.1 dekt waarden, script draaien, read-only verifiëren,
  cold-startgedrag en terugdraaien (incl. `SUSPEND_TIMEOUT_SECONDS=0`/`MAX_CU=8`).
  `deploy/README.md`, `specs/backend-functional-spec.md`, `specs/frontend-spec.md`
  en `docs/onboarding-senior-developer.md` zijn consistent bijgewerkt.
- **AC9**: `RssScheduler`, `EventScheduler` en `EventVideoScheduler` zijn 0 regels
  gewijzigd; `RssRefreshPipeline` alleen een KDoc-regel.
- **Live sanity preview**: `/` → 200, `/actuator/health` → 200 (UP, db UP),
  `/api/feed` zonder token → 403.

Geen browser-screenshots gemaakt: de diff bevat 0 regels Dart/frontend-code
(alleen `specs/frontend-spec.md` als tekst), dus er is geen UI-gedrag gewijzigd om
visueel te bewijzen. Op deze runner zijn Playwright/Chromium ook niet beschikbaar
en bestaat `/work/screenshots` niet.

Geen bevindingen die de story blokkeren. Geen code, tests of infra gewijzigd door de
tester; alleen dit worklog.
