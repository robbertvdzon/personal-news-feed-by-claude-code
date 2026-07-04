# Onboarding — senior ontwikkelaar (backend)

*Voor wie nieuw op dit project komt en snel zelfstandig wijzigingen wil kunnen maken en reviews wil kunnen doen. Leestijd: ~20 minuten. Paden zijn relatief aan `newsfeedbackend/newsfeedbackend/` tenzij anders vermeld.*

Aanvullende documenten: [runbook.md](../runbook.md) (operatie, deployment, secrets, DB-toegang), `specs/` (functionele en technische specs, OpenAPI-contract), [kwaliteitsanalyse-backend.md](kwaliteitsanalyse-backend.md) (de analyse van juli 2026 die tot de huidige opzet leidde).

## 1. Wat is dit systeem

Een zelf-gehoste, persoonlijke nieuwslezer met AI-curatie voor meerdere gebruikers. De backend (Kotlin, Spring Boot 4, Maven) doet vier dingen:

1. **RSS-curatie** — haalt periodiek de RSS-feeds van de gebruiker op, laat AI elk artikel samenvatten en categoriseren, laat AI selecteren wat interessant genoeg is, en genereert voor geselecteerde artikelen een uitgebreid Nederlands feed-item.
2. **Podcast-ingestion** — volgt podcast-feeds, maakt van nieuwe afleveringen eerst snel een kaartje op basis van de show-notes en verrijkt dat later asynchroon met een Whisper-transcript.
3. **Event-discovery** — zoekt wekelijks (Tavily-websearch + AI-extractie) naar tech-conferenties die passen bij de voorkeuren van de gebruiker, inclusief video's en samenvattingen daarvan.
4. **Adhoc-verzoeken & podcastgeneratie** — de gebruiker kan losse nieuwsverzoeken doen en er kan een eigen AI-podcast ("DevTalk") gegenereerd worden met TTS-stemmen.

Alle data is **per gebruiker** gescheiden (username is overal de partitiesleutel). Twee Flutter-frontends praten met deze backend via REST + WebSocket; het API-contract staat in `specs/openapi.yaml`.

## 2. Architectuur: Spring Modulith met feature-modules

De code is één deploybare Spring Boot-app, intern opgedeeld in **feature-modules**: elke directe subpackage van `com.vdzon.newsfeedbackend` is een module (Spring Modulith).

| Module | Verantwoordelijkheid |
|--------|----------------------|
| `auth` | registratie, login, JWT, wachtwoord- en accountbeheer |
| `rss` | RSS-items: fetchen, AI-samenvatting/-selectie, de refresh-pipeline |
| `feed` | de gecureerde persoonlijke feed (feed-items, gelezen/ster/cleanup) |
| `podcast_source` | ingestion van podcast-feeds (twee-fasen: show-notes → transcript) |
| `podcast` | generatie van de eigen AI-podcast + vertaling |
| `events` | wekelijkse event-discovery, event-video's en samenvattingen |
| `request` | (adhoc) nieuwsverzoeken en hun statusadministratie |
| `settings` | gebruikersvoorkeuren: categorieën, feed-lijsten, event-voorkeuren/denylist |
| `admin` | gebruikersbeheer en AI-kosten-dashboards (alleen rol `admin`) |
| `shared` | publieke read-only feed voor de reader-app (geen auth) |
| `ai` | OpenAI-clients (chat, Whisper), model-config, prijsberekening, `AiJson` |
| `search` | Tavily-websearch-client |
| `media` | ffmpeg-audiotranscoding |
| `external_call` | audit-log van álle externe calls (kosten, duur, status) |
| `websocket`, `storage`, `common`, `version` | live statusupdates, JDBC-jsonb-helpers, gedeelde exceptions/config, versie-endpoint |

### De regels (afgedwongen, niet vrijblijvend)

- **Binnen een module**: `api/` (controllers, DTO's) → `domain/` (businesslogica) → `infrastructure/` (repositories, HTTP-clients). Alles in die submappen is **privé** voor de module.
- **Tussen modules**: je mag alleen types gebruiken die op de **package-root** van een andere module staan — dat is haar publieke API (bv. `feed/FeedService.kt`, `rss/RssEvents.kt`, `auth/AuthService.kt`). Nooit rechtstreeks een repository of infrastructure-class van een andere module injecteren.
- **Geen cycles** tussen modules. De afhankelijkheidsrichtingen zijn bewust: `podcast_source` → `rss` (podcasts leveren rss-cards áán), `rss` → `feed` (de pipeline produceert feed-items), iedereen → `common`/`ai`, en `common` kent **geen enkele** businessmodule.
- **`ModuleStructureTest`** (in `src/test/kotlin`) draait `ApplicationModules.detectViolations()` bij elke build. De allowlist is **leeg** en hoort leeg te blijven: als jouw wijziging deze test laat falen, is de import-richting fout — los het op met een publiek interface/event op de package-root van de aangeroepen module (voorbeelden: `PodcastEpisodeLookup`, `ExternalCallQuery`, `PodcastTranscriptLookup`).
- **Cross-module async** gaat via Spring-events (`ApplicationEventPublisher` + `@EventListener @Async`). De event-types staan op de package-root van de eigenaar (bv. `rss/RssEvents.kt`).

### Waarom deze keuzes (zodat je ze niet "verbetert" zonder reden)

- **Modulith i.p.v. microservices**: één persoon, één deploy, maar wél afdwingbare grenzen. Het geeft 90% van de ontwerpdiscipline zonder de operationele kosten.
- **JDBC i.p.v. JPA**: repositories schrijven expliciete SQL (`NamedParameterJdbcTemplate`, jsonb-helpers in `storage/`). Geen lazy-loading-verrassingen, migraties via Flyway zijn de enige bron van schemawaarheid.
- **`@Service`/`@Component` op domain-classes**: bewust pragmatisch. Puristische clean architecture houdt Spring uit het domein; hier is dat losgelaten omdat de e2e-teststrategie (hele app starten) het isolatievoordeel al levert. Niet "opschonen".
- **DTO's in de `api/`-laag**: controllers geven DTO's terug (`feed/api/dto/FeedItemDto`, `rss/api/dto/RssItemDto`, `shared/api/dto/SharedFeedItemDto` + `toDto()`-mappers), niet de domeinmodellen zelf. Het JSON-contract leeft dus in de DTO (daar horen ook de `@JsonProperty`-annotaties); het domeinmodel kan vrij evolueren. Dit patroon is doorgevoerd voor feed/rss/shared — de contract-zware endpoints; de overige modules (settings, requests, admin, events) geven hun modellen nog direct terug en volgen dit patroon zodra ze worden aangeraakt. Kleine request-bodies (bv. `FeedbackBody`) zijn al DTO's.
- **Eén injecteerbare `Clock`** (`common/ClockConfig`): tijd-afhankelijke logica (cleanup-cutoffs) gebruikt `Instant.now(clock)` zodat tests tijd kunnen bevriezen. Volg dat patroon bij nieuwe tijd-logica.
- **`external_call` logt álle externe calls** (AI, TTS, Tavily, RSS-fetches) met kosten in USD naar de `external_calls`-tabel. Nieuwe externe integraties horen óók via `ExternalCallLogger` te loggen — het admin-kostendashboard leunt erop.

## 3. De hoofdflows

### RSS-refresh (`rss/domain/RssRefreshPipeline`)

Trigger: uurlijkse cron (`RssScheduler`), de refresh-knop in de UI (`POST /api/rss/refresh` → `RssRefreshRequested`-event), of een podcast-promotie. De pipeline is een dunne orkestrator met per-user `ReentrantLock` (geen dubbele runs) en delegeert aan drie stap-classes:

1. `RssFetcher` haalt alle feeds parallel op (artikelen ouder dan 4 dagen worden genegeerd);
2. `RssSummarizer` — één AI-call per nieuw artikel: NL-samenvatting + categorie + topics;
3. `FeedSelector` — één AI-call voor de hele batch: welke artikelen zijn de feed waard (met liked/disliked/starred-historie en topic-history als context);
4. `FeedItemGenerator` — per geselecteerd artikel: volledige tekst ophalen (`ArticleFetcher`, of het podcast-transcript) en een uitgebreid NL feed-item genereren.

De status is voor de gebruiker zichtbaar via de request-administratie (`hourly-update-<user>` in `news_requests`) en WebSocket-updates.

### Podcast-ingestion (`podcast_source/domain/…`) — twee fasen

Fase 1 (`PodcastShowNotesProcessor`, direct bij ontdekking): nieuwe afleveringen (top-7-window van de feed, GUID-dedup) krijgen binnen seconden een kaartje in de RSS-tab op basis van de show-notes (`summary_source='show_notes'`). Fase 2 (`PodcastTranscriptWorker` → `PodcastTranscriptProcessor`, elke 2 min max één episode): MP3 downloaden, zo nodig comprimeren (`media/AudioTranscoder`, Whisper-limiet 25 MiB), Whisper-transcript, rijkere hersamenvatting, daarna feed-promotie via het `PodcastPromotionRequested`-event. Whisper-rate-limits → backoff 5m/15m/45m/24h; na 24h zonder transcript wordt alsnog op show-notes gepromoveerd. De statusmachine staat in `PodcastEpisodeStatus` en is leidend — respecteer haar in elke wijziging.

### Event-discovery (`events/domain/…`)

Wekelijkse cron (zondag). `EventDiscoveryPipeline` orkestreert: `EventExtractor` (drie strategieën: seed-namen van de gebruiker, "vergelijkbare events", per-categorie) → `EventDateEnricher` (datums valideren/bijzoeken) → `EventPersister` (dedup op genormaliseerd id, denylist-filter, upsert) → `EventFeedAnnouncer` (feed-item "nieuw event gevonden"). Een event verwijderen zet het op de per-user **denylist** zodat discovery het niet opnieuw aanbiedt.

### Wat het kost

Elke AI-actie heeft een geconfigureerd model (`app.ai.models.*`, override via `PNF_AI_MODEL_*`-env-vars; fallback centraal in `AiModelProperties.modelOrDefault`). Tarieven staan in `app.ai.pricing.*`; `external_call` rekent per call de kosten uit. Bij het wijzigen van prompts of het toevoegen van AI-stappen: check het kosteneffect in het admin-dashboard.

## 4. Teststrategie: hele app, gefakete buitenwereld

**De kern:** vrijwel alle functionaliteit wordt getest met e2e-tests die de **volledige app** starten — echte Spring-context, echte Postgres (Testcontainers), echte Flyway-migraties, echte HTTP-security — waarbij **alleen externe diensten gefaked** zijn. Losse unit tests zijn er alleen voor pure logica (parsers, prijsberekening, `AiJson`).

- `mvn test` → snelle unit tests + `ModuleStructureTest` (~15s, geen Docker).
- `mvn verify` → ook de e2e-suite (`src/test/kotlin/…/e2e/`, ~7 min, Docker vereist). Failsafe forkt per testklasse een eigen JVM + verse database.

Het harnas (`e2e/E2eTestBase` + `E2eTestConfig`):

- **`FakeOpenAiChatClient`** vervangt als `@Primary`-bean de echte OpenAI-client. Zonder scripting geeft hij per `action` een plausibel default-antwoord (bv. feed-selectie: "alles geselecteerd"); een test script gedrag met `openAi.onAction(ACTION) { call -> "…json…" }` en assert op `openAi.callsFor(action, username)`.
- **`FakeContentServer`** is een embedded HTTP-server voor alles wat de app zelf fetcht: RSS-/podcast-feeds, artikel-HTML, Tavily-API-antwoorden, TTS-audio-bytes. Feed-URL's zijn user-config, dus een test registreert gewoon een `localhost`-URL; de base-URL's van OpenAI/Tavily/ElevenLabs wijzen in tests óók naar deze server, zodat een gemiste seam nooit het echte internet raakt.
- **Isolatie door unieke usernames**, niet door table-truncates: alle data is per-user, dus tests zien elkaar niet en kunnen binnen één klasse parallel aan dezelfde context hangen.
- **Async flows**: gebruik altijd de `await { … }`-helper (Awaitility), nooit kale sleeps.

**Een nieuwe feature testen** = een nieuwe `XxxE2eTest : E2eTestBase()` die het gedrag via HTTP uitoefent, plus eventueel een unit test als er een puur stukje logica in zit. Kijk `RssRefreshE2eTest` af als voorbeeld van een volledige pipeline-test.

Dit harnas is gemodelleerd naar de succesvolle opzet in de `softwarefactory`-repo van de eigenaar. Het heeft z'n waarde al bewezen: het vond een race-condition waardoor podcast-promoties stilletjes verloren gingen, en de ontbrekende Jackson 3-Kotlin-module in de HTTP-laag.

## 5. Veilig wijzigen — de werkwijze

1. **Draai `mvn verify` vóór je begint** zodat je zeker weet dat je van groen vertrekt.
2. Schrijf voor nieuw gedrag eerst (of tegelijk) een e2e-test; voor een bugfix eerst een falende test die de bug aantoont.
3. Houd je aan de module-regels (§2). Twijfel je waar iets hoort? De vraag is "wie is de eigenaar van dit gegeven?" — die module krijgt de logica, de rest praat via haar publieke API of events.
4. `mvn verify` groen → committen. CI (`backend-tests.yml`) draait `mvn verify` op elke PR en push naar main; de `main`-branch is tegelijk de deploy-branch (CI bouwt images, ArgoCD rolt uit — zie runbook §3).
5. **Let op met migraties**: prod en alle PR-previews delen één Neon-database. Een `Vxx__*.sql` raakt prod-data direct bij de eerstvolgende deploy.

### Review-checklist

- [ ] Module-grenzen: geen imports uit `api/domain/infrastructure` van een ándere module (de build faalt er ook op — maar check de *richting* ook op logica).
- [ ] Per-user-scheiding intact: elke query/flow heeft `username` als sleutel.
- [ ] Async correct: `@Async` alleen op public methods van geïnjecteerde beans (interne `this.foo()`-calls omzeilen de Spring-proxy); per-user locks waar dubbele runs schade doen; geen event dat bij lock-contentie stilletjes verloren gaat (wacht met timeout, zie `promoteSingleItem`).
- [ ] Externe calls gelogd via `ExternalCallLogger` (kostenbewaking) en gemockt in e2e-tests.
- [ ] AI-antwoorden geparsed via `AiJson.extract` + expliciete fallbacks (een LLM-antwoord is nooit gegarandeerd geldig JSON).
- [ ] Foutafhandeling: geen nieuwe `!!` op waardes die extern of concurrent kunnen wijzigen; geen lege catch-blokken (minimaal een debug-log); verwachte fouten via de `@ResponseStatus`-exceptions uit `common/Exceptions.kt`.
- [ ] Tijd via de geïnjecteerde `Clock` als het gedrag tijd-afhankelijk is.
- [ ] API-wijziging? Het contract leeft in de DTO's (`api/dto/`) — pas die aan, werk `specs/openapi.yaml` bij en bedenk wat oude Flutter-clients doen (velden weglaten is veiliger dan hernoemen). Nieuwe endpoints geven DTO's terug, geen domeinmodellen.
- [ ] Nederlandse gebruikersteksten (foutmeldingen, AI-output), Engelse identifiers.

## 6. Valkuilen die je een middag kunnen kosten

- **Jackson = Jackson 3 (`tools.jackson`), overal.** De app-code gebruikt de auto-geconfigureerde Boot-mapper; importeer nooit `com.fasterxml.jackson.databind` — dat is de oude Jackson 2, die alleen nog als interne runtime-dependency van jjwt op het classpath staat (niet aan koppelen). De annotaties (`@JsonProperty`) komen wél uit `com.fasterxml.jackson.annotation` — dat package deelt Jackson 3 met Jackson 2.
- **Stale classes na verwijderen/hernoemen van bronbestanden.** De incrementele Kotlin-build ruimt `.class`-files van verwijderde bronbestanden niet op; een zombie-class kan de Spring-context laten crashen met verwarrende bean-fouten. Na het verwijderen of verplaatsen van classes: `mvn clean verify`. (Kostte ons twee keer een debugsessie.)
- **De rol zit in het JWT.** Na een rolwijziging (admin ↔ user) werkt het oude token gewoon door tot de gebruiker opnieuw inlogt. Admin-tests loggen daarom expliciet opnieuw in.
- **ShedLock + schedulers.** Cron-jobs (`@Scheduled`) zijn cluster-safe via de `shedlock`-tabel. Een nieuwe scheduled job zonder `@SchedulerLock` draait bij meerdere replica's dubbel.
- **`gpt-5.4*` modelnamen** in `application.properties` zijn de actuele productie-defaults; wijzig modellen via `PNF_AI_MODEL_*`-env-vars, niet in code.
- **PR-previews delen de prod-DB.** Lokaal ontwikkelen gaat tegen een eigen Postgres (`docker-compose.dev.yml`, zie runbook §4) — maar de PR-preview-omgevingen op OpenShift praten nog met **dezelfde Neon-DB als prod**. Een migratie in een PR raakt dus prod-data zodra de preview deployt. Geplande verbetering: Neon-branches per preview.
- **Er pusht een tweede committer.** Een lokale "software factory" van de eigenaar pusht af en toe `SF-…`-commits naar `main`; rebase vóór elke push.

## 7. Eerste dag — praktisch

```bash
git clone <repo> && cd personal-news-feed-by-claude-code/newsfeedbackend/newsfeedbackend
mvn verify            # bewijs dat alles werkt: unit + e2e (Docker moet draaien)
```

Daarna: runbook §4 voor lokaal draaien met frontend, `docs/factory/secrets-local.md` voor welke env-vars de backend kent, en `specs/backend-functional-spec.md` als je het functionele gedrag van een flow tot in detail wilt nalezen. Begin je eerste wijziging klein — een extra e2e-test schrijven voor een flow die je wilt begrijpen is de snelste manier om het systeem te leren kennen.
