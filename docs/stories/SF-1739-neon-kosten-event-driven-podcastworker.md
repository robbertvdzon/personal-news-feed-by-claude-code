# SF-1739 - Verlaag Neon-kosten met event-driven podcastworker, scale-to-zero en compute-cap

## Story

Verlaag Neon-kosten met event-driven podcastworker, scale-to-zero en compute-cap

<!-- refined-by-factory -->

## Samenvatting

De database-rekening van dit project loopt vrijwel volledig op de rekentijd van de
database, niet op de opslag. Twee dingen houden die rekentijd onnodig hoog: de
podcastverwerking kijkt elke twee minuten in de database of er werk ligt — ook als
er niks is — en de database mag zichzelf nooit uitzetten en tot acht keer haar
basisformaat opschalen.

Deze story maakt de podcastverwerking meldingsgestuurd: zodra er transcriptwerk
ontstaat, start het direct. Een controleronde draait nog hoogstens één keer per uur
om vergeten of mislukte taken alsnog op te pakken. Daarnaast leggen we vast dat de
database na vijf minuten stilte mag slapen en hooguit één rekeneenheid gebruikt.
Functioneel merkt een gebruiker hier niets van, behalve een korte wachttijd bij het
eerste bezoek nadat de database heeft geslapen.

## Scope

### 1. Event-driven transcriptverwerking (backend)

- Publiceer een nieuw applicatie-event zodra een aflevering transcriptwerk nodig
  krijgt. Het publicatiepunt is `PodcastShowNotesProcessor` op het moment dat de
  status naar `NEEDS_TRANSCRIPT` gaat.
- Voeg een `@EventListener @Async`-consument toe die de bestaande
  `PodcastTranscriptProcessor.processTranscript(...)` aanroept. Volg het bestaande
  repo-patroon (`RssRefreshPipeline`, `EventDiscoveryPipeline`).
- Verwijder de twee-minuten-`@Scheduled` uit `PodcastTranscriptWorker` en de
  bijbehorende property `app.podcast.transcript-worker.interval-ms`
  (`application.properties:107`); `initial-delay-ms` vervalt mee als hij nergens
  anders wordt gebruikt.
- Behoud: maximaal één aflevering tegelijk in verwerking, de backoff-tabel
  (5m / 15m / 45m / 24h) inclusief `retry_count`/`next_attempt_at`, de
  show-notes-timeout-promotie met `feed_promotion_attempted_at`-marker, en de
  bestaande repository-queries (`findOneReadyForTranscript`,
  `findShowNotesExpiredForPromotion`, `markFeedPromotionAttempted`).

### 2. Recovery/reconciliation-job (backend)

- Eén `@Scheduled(cron = ...)`-job die hoogstens elk uur draait, met
  `@SchedulerLock` conform het patroon van `RssScheduler`.
- De job pakt op: afleveringen die op `NEEDS_TRANSCRIPT` staan en waarvan
  `next_attempt_at` verlopen is (gemist door restart of retry-klaar), plus de
  show-notes-timeout-promotie die nu in de tick zit.
- De job is een vangnet, geen normale route: bij een verse aflevering start de
  verwerking via het event, niet via de job.

### 3. Neon-endpointinstellingen (config + docs)

- Lever een idempotent, herbruikbaar script (bijv. `deploy/neon-endpoint-config.sh`)
  dat via de Neon REST API de read/write-endpoint van het productieproject zet op
  `suspend_timeout_seconds=300`, `autoscaling_limit_min_cu=0.25`,
  `autoscaling_limit_max_cu=1`, en daarna de effectieve waarden read-only terugleest
  en print. Volg het env-var- en API-patroon van
  `deploy/preview-ns-labeller/labeller.sh` (`NEON_API`, `NEON_API_KEY`,
  `NEON_PROJECT_ID`).
- Het script leest credentials uitsluitend uit environment-variabelen, logt geen
  sleutels of tokens, en committeert geen credentials.
- Runbook-sectie beschrijft: hoe je het script draait, welke waarden verwacht worden,
  hoe je ze read-only verifieert, en hoe je terugdraait naar de oude waarden.

### 4. Scale-to-zero niet blokkeren (backend + deploy)

- Hikari zo instellen dat de pool daadwerkelijk leegloopt bij inactiviteit:
  `spring.datasource.hikari.minimum-idle=0` en een `idle-timeout` ruim onder de
  suspend-timeout van 300s. (Nu is `minimum-idle` niet gezet en dus gelijk aan
  `maximum-pool-size=5`, waardoor `idle-timeout=600000` in HikariCP niet werkt en er
  permanent vijf verbindingen openstaan.)
- Geen `keepaliveTime`, geen validatie-query op een timer, geen `@Scheduled` die
  vaker dan elk uur de database raakt.
- Borgen dat de Kubernetes-probes de database niet aanraken: readiness/liveness
  blijven op `/actuator/health/readiness` en `/actuator/health/liveness` (de Spring
  Boot-standaardgroepen zonder db-indicator) en er wordt geen
  `management.endpoint.health.group.*`-configuratie toegevoegd die de db-indicator in
  een probe-groep trekt.

### 5. Documentatie

- Runbook en de betrokken spec-teksten die de twee-minuten-worker beschrijven
  (o.a. `specs/backend-functional-spec.md`, `docs/onboarding-senior-developer.md`)
  bijwerken naar de event-driven flow + uurlijkse recovery.
- Neon-instellingen, verificatiecommando en cold-startgedrag documenteren in het
  runbook.

## Acceptance criteria

1. Een aflevering die transcriptwerk nodig krijgt, start de transcriptverwerking
   direct via een applicatie-event; de verwerking wacht niet op de recovery-job.
2. Er bestaat geen `@Scheduled` meer die vaker dan één keer per uur de database
   bevraagt; de property `app.podcast.transcript-worker.interval-ms` bestaat niet meer.
3. De recovery-job draait hoogstens elk uur, staat onder `@SchedulerLock`, en pakt
   afleveringen op die `NEEDS_TRANSCRIPT` zijn met een verlopen `next_attempt_at` —
   ook als de instantie tussentijds is herstart.
4. De volgende gedragingen blijven werken en zijn met tests afgedekt: retry-backoff
   (5m / 15m / 45m / 24h met `retry_count`/`next_attempt_at`), show-notes-timeout-
   promotie inclusief de `feed_promotion_attempted_at`-marker (geen herhaalde
   AI-calls), maximaal één aflevering tegelijk in verwerking, en idempotentie zodat
   dezelfde aflevering niet twee keer parallel wordt opgepakt.
5. `spring.datasource.hikari.minimum-idle=0` staat gezet met een `idle-timeout`
   onder 300s; er is geen keepalive/validatie-timer die de database wakker houdt.
6. De Kubernetes-probes raken de database niet: readiness/liveness gebruiken de
   standaard Spring-groepen en er is geen health-groepconfiguratie die de
   db-indicator in een probe opneemt.
7. Er is een idempotent script in de repo dat de Neon-endpoint zet op
   `suspend_timeout_seconds=300`, min 0,25 CU en max 1 CU, en dat de effectieve
   waarden read-only terugleest en toont. Het script bevat geen credentials en logt
   geen secrets.
8. Het runbook beschrijft de nieuwe verwerkingsflow, de recovery-job, de
   Neon-instellingen, het draaien én read-only verifiëren van het script, het
   verwachte cold-startgedrag na suspend, en hoe je terugdraait.
9. De bestaande hourly RSS-refresh, daily summary en beide weekly event-jobs blijven
   ongewijzigd werken.
10. `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend` is groen (inclusief de
    Testcontainers-e2e-tests, o.a. `PodcastIngestE2eTest`).

## Aannames

- **Toepassen op productie is een operatorstap.** `NEON_API_KEY` en
  `NEON_PROJECT_ID` zitten in het cluster-SealedSecret en zijn niet beschikbaar in de
  factory-omgeving. De story levert daarom het script + de runbookprocedure; het
  daadwerkelijk uitvoeren tegen het productieproject en het vastleggen van de
  teruggelezen waarden doet de operator/PO. De originele AC "Neon rapporteert
  `suspend_timeout_seconds=300` en max 1 CU" is zo geherformuleerd (AC 7 + 8).
- **Max 1 CU.** Er is geen loadtest of productiemeting beschikbaar die 1 CU
  ontoereikend maakt, dus we kiezen 1 CU. De 2 CU-uitzondering uit de story blijft
  alleen open voor de operator, met onderbouwing in het runbook.
- **Retries starten via een geplande hertrigger, niet via een poll.** Om de
  backoff-semantiek intact te houden mag de listener een eenmalige, in-memory
  hertrigger inplannen op `next_attempt_at` (Spring `TaskScheduler`), zodat een
  5-minutenretry ook echt na ~5 minuten start. Dat is geen periodieke poll. De
  uurlijkse recovery-job is het vangnet voor hertriggers die door een restart
  verloren gaan; wordt géén in-memory hertrigger geïmplementeerd, dan is een retry
  die tot een uur later start acceptabel.
- **Één instantie.** De deployment draait `replicas: 1` met strategy `Recreate`.
  ShedLock blijft op de recovery-job; voor de event-listener volstaat
  in-proces-serialisatie (lock/semafoor) plus een status-/conditiecontrole in de
  database, zonder nieuwe distributed-lock-infrastructuur.
- **Cold start is acceptabel.** Na suspend mag het eerste request de normale Neon-
  cold-startvertraging (orde seconden) hebben. Er wordt geen warmhoudmechanisme
  gebouwd; als de bestaande `connection-timeout=30000` volstaat, blijft die staan.
- **Databaseschema blijft ongewijzigd.** De bestaande kolommen
  (`status`, `next_attempt_at`, `retry_count`, `feed_promotion_attempted_at`) zijn
  voldoende; er is geen Flyway-migratie nodig.
- **Gebruikersverkeer valt buiten scope.** Een openstaande frontend die de backend
  bevraagt kan de endpoint wakker houden; dat is normaal gebruik en wordt niet
  geoptimaliseerd in deze story.

## Eindsamenvatting

## Eindsamenvatting SF-1739 — Verlaag Neon-kosten met event-driven podcastworker, scale-to-zero en compute-cap

**Doel:** de Neon-rekening daalt doordat de database met rust wordt gelaten als er niets te doen is. Voor de eindgebruiker verandert er functioneel niets, behalve een korte cold-startvertraging bij het eerste bezoek nadat de database heeft geslapen.

### Wat is gebouwd

1. **Event-driven transcriptverwerking.** `PodcastShowNotesProcessor` publiceert bij de overgang naar `NEEDS_TRANSCRIPT` het nieuwe event `PodcastTranscriptRequested`. De consument `PodcastTranscriptPipeline` (`@EventListener @Async`, zelfde patroon als `RssRefreshPipeline`) start de transcriptie direct — geen wachttijd meer op een tick.
2. **De 2-minuten-poll is weg.** `PodcastTranscriptWorker` is verwijderd, net als de properties `app.podcast.transcript-worker.interval-ms` en `initial-delay-ms`. In `src/main` staat nu geen enkele `@Scheduled` meer die vaker dan één keer per uur de database raakt.
3. **Uurlijkse recovery-job als vangnet.** `PodcastRecoveryScheduler` (`@Scheduled(cron)` + `@SchedulerLock`, conform `RssScheduler`) pakt afleveringen op met verlopen `next_attempt_at` — ook na een restart — en doet de show-notes-timeout-promotie inclusief de anti-loop-marker.
4. **Scale-to-zero niet meer geblokkeerd.** `spring.datasource.hikari.minimum-idle=0` + `idle-timeout=60000` (ruim onder de suspend-timeout van 300s). Zonder `minimum-idle=0` stonden er permanent vijf verbindingen open. Geen keepalive of validatie-timer toegevoegd. De Kubernetes-probes blijven op de standaard readiness/liveness-groepen zonder db-indicator (bewust ongewijzigd).
5. **Neon-script.** `deploy/neon-endpoint-config.sh`: idempotent, zet `suspend_timeout_seconds=300`, min 0,25 CU en max 1 CU, patcht alleen bij afwijking en leest de effectieve waarden read-only terug (`--verify` doet uitsluitend GET's). Credentials komen uitsluitend uit environment-variabelen; er worden geen sleutels gelogd of gecommit.
6. **Documentatie.** Runbook §6.1 (nieuwe flow, script draaien, read-only verifiëren, cold-startgedrag, terugdraaien), plus `specs/backend-functional-spec.md`, `specs/frontend-spec.md`, `docs/onboarding-senior-developer.md` en `deploy/README.md`.

### Gemaakte keuzes

- **Geen in-memory hertrigger** op `next_attempt_at`. Een retry start dus uiterlijk bij de eerstvolgende uurlijkse run in plaats van precies na 5 minuten. De story stond dit expliciet toe; het scheelt een tweede timer-mechanisme met eigen faalgedrag.
- **Max 1 CU** gekozen (niet 2), omdat er geen meting is die 1 CU ontoereikend maakt. Het runbook laat de uitzondering open voor de operator.
- **Eigen single-threaded executor** (`podcastTranscriptExecutor`) voor de transcript-listener, zodat een minutenlange Whisper-run de snelle show-notes-verwerking niet ophoudt. Een proceswijde lock plus een herlezing van de rij per event borgt "maximaal één tegelijk" en idempotentie.
- **Recovery-job publiceert alleen events**, doet zelf geen Whisper-werk, met een cap van 10 afleveringen per run — alles loopt zo serieel door dezelfde pipeline.
- **Geen schemawijziging**, geen Flyway-migratie: de bestaande kolommen volstaan.

### Wat is getest

- `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend`: **BUILD SUCCESS** — 100 unit-tests + 67 e2e-tests (Testcontainers), 0 failures/errors.
- Nieuw: `PodcastTranscriptPipelineTest` (8) — volledige backoff-tabel 5m/15m/45m/24h, backoff-wachtkamer, idempotentie bij dubbele events, exception-isolatie en een concurrency-test met 4 threads die bewijst dat er nooit twee afleveringen tegelijk verwerkt worden.
- Nieuw: `PodcastRecoverySchedulerTest` (8) — hertriggeren van achterstallige afleveringen, cap per run, marker-vóór-event (anti-loop), promotie-timeout uit de property.
- Nieuw: 2 e2e's in `PodcastIngestE2eTest` — fase 2 start direct via het event (recovery-cron uit), en de recovery-job pakt een door een restart gemiste aflevering alsnog op.
- **Live geverifieerd op preview** `pnf-pr-199`: `/actuator/prometheus` toont `hikaricp_connections_min = 0.0` (dus `minimum-idle=0` is echt actief) en `/actuator/health/{readiness,liveness}` geven `UP` zonder db-component (probes raken de database niet).
- Reviewer akkoord, geen blockers; alle 10 acceptatiecriteria per stuk nagelopen.

### Bewust niet gedaan

- **Het script is niet tegen productie gedraaid.** `NEON_API_KEY`/`NEON_PROJECT_ID` zitten in het cluster-SealedSecret en zijn niet beschikbaar in de factory. **Actie voor de operator/PO:** `deploy/neon-endpoint-config.sh` draaien volgens runbook §6.1 en de teruggelezen waarden vastleggen. Pas daarna daalt de rekening daadwerkelijk.
- Geen warmhoudmechanisme tegen cold starts — de Neon-cold-start (orde seconden) is geaccepteerd.
- Geen frontend-wijzigingen (0 regels Dart), dus geen browser-/screenshotverificatie.
- Optimalisatie van gebruikersverkeer dat de database wakker houdt viel buiten scope.

### Aandachtspunten voor later (niet blokkerend)

- `PodcastEpisodeRepository.findOneReadyForTranscript` heeft geen callers meer; de story vroeg 'm te behouden. Kandidaat voor een opruimstory.
- Bij een achterstand van >10 afleveringen loopt de wachtrij met ~10 per uur leeg; met de event-driven happy path onwaarschijnlijk.

```json
```

```json
```
