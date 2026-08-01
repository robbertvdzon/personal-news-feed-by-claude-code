# SF-1739 — Verlaag Neon-kosten met event-driven podcastworker, scale-to-zero en compute-cap

Subtaak SF-1740 (development). Doel: de database mag met rust gelaten worden
als er niets te doen is, zodat Neon kan suspenden en de compute-rekening daalt.

## Stappenplan

- [x] `.task.md`, `docs/factory/*`, `specs/backend-*` en de bestaande podcast-flow gelezen
- [x] Event-driven transcriptverwerking: `PodcastTranscriptRequested` + `@EventListener @Async`-consument
- [x] 2-minuten-`@Scheduled` en de properties `interval-ms`/`initial-delay-ms` verwijderd
- [x] Uurlijkse recovery/reconciliation-job met `@SchedulerLock`
- [x] Hikari `minimum-idle=0` + `idle-timeout` onder de suspend-timeout
- [x] Probes gecontroleerd (standaard readiness/liveness, geen db-indicator) — geen wijziging nodig
- [x] `deploy/neon-endpoint-config.sh` (idempotent, read-only verificatie)
- [x] Documentatie: runbook §6.1, backend-functional-spec, onboarding, frontend-spec, deploy/README
- [x] Tests geschreven (unit + e2e) en het volledige vangnet gedraaid

## Wat is er gedaan en waarom

**1. Event-driven start.** `PodcastShowNotesProcessor` publiceert bij de overgang
naar `NEEDS_TRANSCRIPT` het nieuwe event `PodcastTranscriptRequested`
(`podcast_source/PodcastSourceEvents.kt`, moduleniveau zoals `rss/RssEvents.kt`).
`PodcastTranscriptPipeline` consumeert het met `@EventListener @Async` — hetzelfde
patroon als `RssRefreshPipeline`/`EventDiscoveryPipeline` — en roept de
ongewijzigde `PodcastTranscriptProcessor.processTranscript(...)` aan. De
backoff-tabel (5m/15m/45m/24h met `retry_count`/`next_attempt_at`) is 1-op-1
meeverhuisd uit de oude worker.

**2. Max één tegelijk + idempotentie.** De listener draait op een nieuwe,
expliciet single-threaded executor `podcastTranscriptExecutor` (los van
`podcastTaskExecutor`, zodat een minutenlange Whisper-run de snelle
show-notes-cards niet ophoudt) én neemt een proceswijde `ReentrantLock`. Per
event wordt de rij opnieuw uit de database gelezen: alleen `NEEDS_TRANSCRIPT`
met verlopen `next_attempt_at` wordt opgepakt, dus dubbele events zijn gratis.

**3. Recovery-job.** `PodcastTranscriptWorker` is vervangen door
`PodcastRecoveryScheduler`: `@Scheduled(cron = ${app.podcast.recovery.cron:0 5 * * * *})`
met `@SchedulerLock`, conform `RssScheduler`. De job doet zelf geen Whisper-werk
maar publiceert hetzelfde event voor maximaal 10 achterstallige afleveringen, zodat
alles serieel door dezelfde pipeline loopt. De show-notes-timeout-promotie is
ongewijzigd meegenomen, inclusief de anti-loop-volgorde (`markFeedPromotionAttempted`
vóór `PodcastPromotionRequested`).

**Bewuste keuze:** géén in-memory hertrigger via `TaskScheduler`. Een retry start
dus op z'n laatst bij de eerstvolgende uurlijkse run. De story staat dat expliciet
toe en het scheelt een tweede timer-mechanisme met eigen faalgedrag.

**4. Scale-to-zero.** `spring.datasource.hikari.minimum-idle=0` +
`idle-timeout=60000` (ruim onder `suspend_timeout_seconds=300`). Zonder
`minimum-idle=0` is `minimumIdle == maximumPoolSize (5)` en doet `idle-timeout`
in HikariCP niets. Geen keepalive, geen validatie-timer. Probes stonden al goed
(`/actuator/health/{readiness,liveness}`, geen health-groepconfiguratie) — bewust
niet aangeraakt.

**5. Neon-script.** `deploy/neon-endpoint-config.sh` volgt het env-var-/API-patroon
van `preview-ns-labeller/labeller.sh`, zoekt de read/write-endpoint van de
default-branch, patcht alleen bij afwijking en leest de effectieve waarden
read-only terug (`--verify` patcht nooit). Uitvoeren tegen productie is een
operatorstap; runbook §6.1 beschrijft draaien, verifiëren, cold start en
terugdraaien.

## Tests

- `PodcastTranscriptPipelineTest` (8): backoff-tabel (5m/15m/45m/24h met
  `retry_count`/`next_attempt_at`), backoff-wachtkamer, idempotentie bij dubbele
  events, verdwenen aflevering, exception-isolatie en een concurrency-test die
  bewijst dat er nooit twee afleveringen tegelijk in verwerking zijn.
- `PodcastRecoverySchedulerTest` (8): hertriggeren van achterstallige
  afleveringen, cap per run, marker-vóór-event, anti-loop bij een mislukte marker,
  overslaan zonder `rss_item_id`, promotie-timeout uit de property en
  stap-isolatie.
- `PodcastIngestE2eTest`: twee nieuwe e2e's — `transcribeEnabled=true` start fase 2
  direct via het event (met de recovery-cron uit), en de recovery-job pakt een door
  een restart gemiste aflevering alsnog op.
