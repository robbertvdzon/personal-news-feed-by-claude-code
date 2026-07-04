# Kwaliteitsanalyse backend (`newsfeedbackend`)

*Datum: 2026-07-04 — analyse van de Kotlin/Spring Boot backend op architectuur, clean code, tests en documentatie. Alle bevindingen zijn geverifieerd in de code; paden zijn relatief aan `newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/`.*

## 1. Eindoordeel in het kort

De backend zit **beter in elkaar dan typische vibe-code**. Er is een bewuste, herkenbare structuur: ±14 feature-modules (Spring Modulith) met per module een `api`/`domain`/`infrastructure`-indeling, nette public interfaces per module, overal constructor injection, en event-gedreven communicatie tussen modules. De documentatie (README, runbook, specs) klopt voor ~95% met de code — dat is uitzonderlijk goed.

De twee grote gebreken:

1. **Er is vrijwel geen testdekking.** 19 unit tests over 3 files, tegenover 106 productie-files. Nul integratietests, nul database-tests, nul API-tests. De kernlogica (RSS-pipeline, podcastgeneratie, auth) is volledig ongetest. Dit is het grootste risico bij elke toekomstige wijziging.
2. **De pipelines zijn god-classes.** Drie classes van 600+ regels die elk 5+ verantwoordelijkheden mengen, met 4× exact gedupliceerde JSON-parselogica.

Cijfer per gebied: architectuur **7/10**, clean code **6/10**, tests **2/10**, documentatie **9/10**.

## 2. Architectuur

### Sterk

- **Modulaire opzet is echt aanwezig**: feed, rss, podcast, podcast_source, auth, settings, admin, events, request, websocket, storage, common, version, ai, external_call. Elke module heeft een public interface op package-root (bv. `feed/FeedService.kt`); repositories zitten netjes in `infrastructure/` en lekken niet naar buiten.
- **Constructor injection overal**, geen field injection — afhankelijkheden zijn expliciet.
- **JDBC in plaats van JPA**: database-mapping is expliciet en geïsoleerd, geen ORM-magie.
- **Spring Events voor async cross-module-communicatie** — het juiste mechanisme voor deze opzet.
- **Configuratie via properties + env-vars**, secrets komen niet in code voor.

### Zwak

| # | Bevinding | Bewijs | Ernst |
|---|-----------|--------|-------|
| A1 | **Circulaire afhankelijkheid `rss` ↔ `podcast_source`**: rss gebruikt `PodcastTranscriptLookup`; podcast_source importeert `RssItem`, `RssItemRepository` en rss-events rechtstreeks | `rss/domain/RssRefreshPipeline.kt`, `podcast_source/domain/PodcastEpisodeProcessor.kt:12-14` | Hoog |
| A2 | **Controller roept infrastructuur van een ándere module aan**: SettingsController injecteert `PodcastFeedFetcher` (infrastructure-component van podcast_source) direct | `settings/api/SettingsController.kt:4` | Hoog |
| A3 | **`common` orkestreert businessmodules**: StartupRunner importeert `AuthService`, `UserRepository` én `RequestService` — common hoort een pure utility-laag te zijn zonder afhankelijkheid op businessmodules | `common/StartupRunner.kt:3-6` | Middel |
| A4 | **Geen DTO-scheiding**: controllers geven domeinmodellen (`FeedItem`, `RssItem`) direct terug als API-response; elke domeinwijziging is meteen een API-wijziging richting de Flutter-apps | `feed/api/FeedController.kt:26` | Middel |
| A5 | **`ModuleStructureTest` ontbreekt**: de docs verwijzen ernaar (`docs/factory/technical-spec.md:25`) maar hij bestaat niet — module-grenzen worden dus nergens afgedwongen, waardoor schendingen als A1/A2 ongemerkt konden ontstaan | `src/test/kotlin/` | Hoog |
| A6 | **Vage modules**: `external_call` (audit-logging? infrastructuur? cross-cutting?), `ai` heeft als enige geen api-laag maar wordt door 7 modules gebruikt, `events` leunt op 9 andere modules | — | Laag |

**Bewuste keuze, geen probleem**: domain-classes dragen `@Service`-annotaties. Puristische clean architecture verbiedt dat, maar voor een Spring Modulith-project is dit een gangbare, pragmatische stijl. Aanbeveling: zo laten en niet "verbeteren" — de winst weegt niet op tegen de churn.

## 3. Clean code (detailniveau)

### De god-classes

| Class | Regels | Gemengde verantwoordelijkheden |
|-------|--------|-------------------------------|
| `podcast_source/domain/PodcastEpisodeProcessor.kt` | 638 | show-notes-samenvatting, transcript-download/Whisper, retry-logica, RSS-item-bouw, feed-promotie, JSON-parsing |
| `rss/domain/RssRefreshPipeline.kt` | 617 | fetchen, parsen, dedup, AI-samenvatting, AI-selectie, feed-item-generatie, topic-history, JSON-parsing |
| `events/domain/EventDiscoveryPipeline.kt` | 600 | Tavily-search, 4 varianten Claude-extractie, dedup/denylist, datumvalidatie, feed-aankondiging, JSON-parsing |

### Concrete vondsten (geverifieerd)

- **`extractJson()` staat 4× letterlijk gekopieerd**: `RssRefreshPipeline.kt:572`, `EventDiscoveryPipeline.kt:564`, `EventVideoDiscoveryPipeline.kt:220`, `PodcastEpisodeProcessor.kt:595`. In `EventVideoDiscoveryPipeline` staat zelfs een comment dat dit "bewust niet uitgefactored" is — begrijpelijke intentie (pipelines ontkoppeld houden), maar een gedeelde pure utility-functie koppelt niets en één bug moet nu op 4 plekken gefixt.
- **Riskante `!!`-derefs die in productie kunnen crashen**: `rss/domain/RssRefreshPipeline.kt:160` (`requests.get(...)!!`), `request/domain/AdhocOrchestrator.kt:91,98` (`.find { … }!!`). De `encoder.encode(...)!!`-gevallen in `AuthServiceImpl`/`AdminServiceImpl` zijn onschuldig (BCrypt geeft nooit null) maar kunnen netter.
- **Foutafhandeling inconsistent**: breed `catch (Exception)` in ~24 files; `RssFetcher` retourneert stilletjes `emptyList()` bij een fetch-fout (fout onzichtbaar voor de gebruiker); drie lege catch-blokken rond resource-cleanup; warn-logs vaak zonder stacktrace. Daartegenover gebruiken `WhisperClient` en `PodcastEpisodeProcessor` juist nette sealed-class-resultaten — dat patroon verdient navolging.
- **Magic values verspreid door de code**: afkap-limieten (`take(200)`, `take(8000)`, `take(180)`, …), hardcoded ElevenLabs voice-ID's (`TtsClient.kt:26-27`), en op ±14 plekken een hardcoded fallback-modelnaam (`?: "gpt-5.4-mini"`) die stil kan downgraden.
- **Logging**: MDC-gebruik (username per pipeline-run) is netjes en er lekken geen secrets. Wel veel `log.info` per artikel/episode in loops — ruis in productie.
- **Positief**: geen TODO/FIXME-vervuiling, geen uitgecommentarieerde code-blokken, nauwelijks dode code.

## 4. Tests

| Metric | Waarde |
|--------|--------|
| Testfiles / testmethodes | 3 / 19 |
| Modules met ≥1 test | 3 van 14 |
| Integratietests (Spring context, DB, MockMvc) | **0** |
| Coverage-tooling (JaCoCo) | niet geconfigureerd |

De drie bestaande testfiles (`PodcastScriptParserTest`, `AiPricingPropertiesTest`, `RssFetcherImageUrlTest`) zijn kwalitatief **goed** — gedragsgericht, met edge-cases. Ze bewijzen dat het team(pje) kán testen; er is alleen bijna niets getest.

**Ongetest en kritiek**: auth (wachtwoord-hashing, JWT, rollen), feed-mutaties (markAllRead, cleanup met datum-cutoffs), de volledige RSS-refresh-pipeline, podcast-ingestion (idempotentie, GUID-dedup), event-discovery, request-annulering.

**Structurele testbaarheidsproblemen** die integratietests nu in de weg zitten:

1. `Instant.now()` / `LocalDate.now()` hardcoded op tientallen plekken — geen `Clock`-injectie, dus tijd-afhankelijke logica (cleanup, retention) is niet deterministisch te testen.
2. HTTP-clients (`RssFetcher`, `TtsClient`, `ArticleFetcher`, `OpenAiChatClient`, `TavilyClient`) bouwen inline hun eigen `HttpClient` — niet af te vangen zonder WireMock op netwerkiveau.
3. Geen Testcontainers/H2-opzet voor database-tests.

De bouwstenen liggen er al: JUnit 5, Mockito, Spring Test, Cucumber én WireMock staan in de `pom.xml`. Er ontbreekt alleen: Testcontainers, JaCoCo, en de tests zelf.

## 5. Documentatie

**Verrassend goed — 95% accuraat.** Steekproeven op endpoints (13 controllers), env-vars, poorten, DB-schema (migraties t/m V15), deployment-flow en lokale dev-commando's kloppen allemaal met de code. De verwijderde software factory is netjes opgeruimd; resterende verwijzingen in `docs/factory/deployment.md` zijn bewust-historische toelichting.

Kleine punten:
- `docs/factory/development.md` en `technical-spec.md` verwijzen naar een `ModuleStructureTest` die niet bestaat (docs melden zelf dat die er nog moet komen — zie A5).
- Runbook mist een one-liner "hoe draai ik de backend-tests" en e2e-setupinstructies.
- Open TODO in `runbook.md:179` over een eventuele aparte DB-tunnel.

## 6. Verbetervoorstellen (geprioriteerd)

Volgorde is bewust: **eerst een vangnet, dan pas verbouwen**. Elke stap laat de build groen en is apart te committen.

### Fase 1 — Vangnet (voorwaarde voor al het andere)

| # | Voorstel | Waarom eerst |
|---|----------|--------------|
| 1.1 | **`ModuleStructureTest` toevoegen** (Spring Modulith `ApplicationModules.verify()`) | Maakt module-grenzen afdwingbaar; documenteert bestaande schendingen (A1–A3) als expliciete uitzonderingen die we daarna één voor één wegwerken |
| 1.2 | **JaCoCo + Testcontainers in de pom** | Meetbaar maken; DB-integratietests mogelijk maken |
| 1.3 | **`Clock` als bean injecteren** i.p.v. `Instant.now()` overal | Kleine mechanische wijziging, ontgrendelt alle tijd-afhankelijke tests |
| 1.4 | **Unit tests voor de service-laag**: AuthServiceImpl, FeedServiceImpl, RssServiceImpl (cleanup!), RequestServiceImpl | Kern-businesslogica, goedkoop te testen met mocks |
| 1.5 | **Integratietests voor de twee belangrijkste flows**: RSS-refresh-pipeline (WireMock voor RSS + OpenAI-stub) en podcast-ingestion (idempotentie/dedup) | Dit zijn de flows die bij refactoring (fase 2) kapot kunnen gaan |

### Fase 2 — Structuur (met het vangnet uit fase 1)

| # | Voorstel |
|---|----------|
| 2.1 | `extractJson()` → één geteste utility in `common` (of `ai`); 4 kopieën weg |
| 2.2 | **Cycle rss ↔ podcast_source breken**: podcast_source publiceert een event met payload i.p.v. rechtstreeks `RssItemRepository` te muteren; transcript-lookup via interface die rss al kent |
| 2.3 | **`StartupRunner` opsplitsen**: auth-initialisatie naar auth-module, request-init naar request-module; common houdt geen businessimports over |
| 2.4 | **SettingsController via een service** laten lopen i.p.v. direct `PodcastFeedFetcher` |
| 2.5 | **`RssRefreshPipeline` opsplitsen** in stap-classes (fetch/dedup → samenvatten → selecteren → feed-item bouwen), pipeline-class wordt dunne orkestrator |
| 2.6 | **`PodcastEpisodeProcessor` opsplitsen**: show-notes-fase en transcript-fase elk hun eigen class |
| 2.7 | **`EventDiscoveryPipeline` opsplitsen**: discovery-strategieën (seed/similar/category) uit de orkestrator trekken |

### Fase 3 — Detailkwaliteit

| # | Voorstel |
|---|----------|
| 3.1 | De 3 échte `!!`-risico's vervangen door expliciete afhandeling (`RssRefreshPipeline.kt:160`, `AdhocOrchestrator.kt:91,98`) |
| 3.2 | Foutafhandeling consistent maken: sealed results of exceptions per laag; stille `emptyList()`-returns en lege catches voorzien van logging; stacktraces in warn/error-logs |
| 3.3 | Magic values naar `@ConfigurationProperties` (afkap-limieten, voice-ID's) en model-fallback centraal in `AiModels` i.p.v. 14× `?: "gpt-5.4-mini"` |
| 3.4 | Log-niveaus in loops naar debug; info alleen voor samenvattende regels |

### Fase 4 — Documentatie & onboarding

| # | Voorstel |
|---|----------|
| 4.1 | Runbook aanvullen (tests draaien, e2e-setup); TODO regel 179 oplossen |
| 4.2 | **Onboarding-document voor nieuwe senior ontwikkelaar** schrijven: architectuur-overzicht, de "waarom" achter keuzes (Modulith, JDBC, events, @Service-in-domain), hoe de pipelines werken, hoe je veilig wijzigt en reviewt |

**Bewust NIET voorgesteld**: @Service uit domain-classes halen (churn zonder praktische winst), DTO-laag voor álle endpoints (alleen zinvol als de API extern gedeeld wordt; eventueel later voor feed/rss), repository-interfaces introduceren (JDBC-repos zijn al goed mockbaar).
