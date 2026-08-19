# Personal News Feed — Backend Technische Specificatie

> **Doel van dit document:** Architectuur, codestructuur, patterns, monitoring en testing van de backend. Voor functioneel gedrag: zie [`backend-functional-spec.md`](./backend-functional-spec.md).

---

## 1. Tech Stack & Dependencies

**Taal/platform:** Spring Boot **4.x**, Kotlin **2.x**, poort 8080.

**Build tool:** Maven (`pom.xml`).

**Belangrijke dependencies:**

| Dependency | Doel |
|-----------|------|
| `tools.jackson.module:jackson-module-kotlin` | JSON-serialisatie (Jackson 3, Kotlin-support; java-time is ingebouwd, geen aparte `jackson-datatype-jsr310`-module meer) — `pom.xml` en vrijwel alle main-code-imports gebruiken de `tools.jackson`-groupId. Uitzondering: `jackson-annotations` (`@JsonProperty` e.d. in DTO's) blijft bewust onder het `com.fasterxml.jackson.annotation`-package (dat package is in Jackson 3 niet hernoemd). Los daarvan gebruikt `jjwt-jackson` (zie hieronder) intern nog Jackson 2 voor JWT-payloads — een bewuste, geïsoleerde afwijking, geen inconsistentie. |
| `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` | JWT aanmaken en valideren |
| `com.rometools:rome` | RSS 2.0 en Atom feeds parsen |
| `spring-boot-starter-websocket` | WebSocket support — **niet** WebFlux |
| `spring-boot-starter-security` | Spring Security voor JWT-filter en CORS |
| `spring-boot-starter-actuator` | Health en metrics endpoints |
| `io.micrometer:micrometer-registry-prometheus` | Prometheus metrics export |
| `org.springframework.modulith:spring-modulith-starter-core` | Modulaire monolith structuur en verificatie |

**Versiebeheer:** de meeste versies komen uit het dependency-management van `spring-boot-starter-parent` (`pom.xml` regel 8–11) — Tomcat, Spring Framework, Logback, Micrometer, de PostgreSQL-driver en Jackson staan dus nergens expliciet gepind. Alleen componenten buiten die BOM hebben een eigen `<version>` (jjwt, rome, ShedLock, kotlinx-coroutines, en de testafhankelijkheden Cucumber/WireMock/Testcontainers/Awaitility) plus de `spring-modulith-bom` in `<dependencyManagement>`.

Een security-bump van een door de parent gemanagede component is daarom **één regelwijziging**: verhoog de parent-versie en voeg géén nieuwe expliciete `<version>`-pin toe (SF-2116 bracht zo 4.0.6 → 4.0.7 en daarmee o.a. `tomcat-embed-core` 11.0.21 → 11.0.22, `spring-websocket` 7.0.7 → 7.0.8 en `micrometer-core` 1.16.5 → 1.16.6). Verifieer de opbrengst met `mvn dependency:tree` (of `unzip -l target/newsfeedbackend-1.0.0.jar` op `BOOT-INF/lib`) in plaats van met de verwachte versietabel uit de story. Er draait geen geautomatiseerde dependency-scan (geen Dependabot/Renovate/OSV in CI); zulke bumps komen uit een handmatige audit.

---

## 2. Projectstructuur & IntelliJ Setup

De Maven-projectroot bevindt zich in `newsfeedbackend/newsfeedbackend/`. Open **deze map** als projectroot in IntelliJ (niet de bovenliggende `newsfeedbackend/`).

IntelliJ detecteert automatisch het Maven-project via `pom.xml`.

### .gitignore (in `newsfeedbackend/newsfeedbackend/`)
```
.idea/
*.iml
target/
```

### API-contract zonder code-generatie
De build kent **geen** code-generatiestap: er staat geen OpenAPI Generator-plugin in `pom.xml` en er is geen `target/generated-sources/openapi/`-map. De controllers zijn met de hand geschreven Spring `@RestController`-klassen die `specs/openapi.yaml` als handmatig onderhouden contract volgen (zie §8).

---

## 3. Spring Modulith — Modulestructuur

De backend gebruikt **Spring Modulith** voor het afdwingen van modulegescheiden architectuur binnen een monolith. Elke module is een top-level package onder `com.vdzon.newsfeedbackend`.

### Modules

| Module | Package | Verantwoordelijkheid |
|--------|---------|----------------------|
| `auth` | `com.vdzon.newsfeedbackend.auth` | Registratie, login, JWT-aanmaak en -validatie, gebruikersbeheer; `AuthService.validateToken(token)` geeft een `AuthenticatedUser` (`username`, `role`) en is de publieke module-API voor kanalen die de servlet-securityketen niet doorlopen (vandaag de WebSocket-handshake) |
| `rss` | `com.vdzon.newsfeedbackend.rss` | RSS-feeds ophalen, AI-verwerking van artikelen, feed-selectie |
| `feed` | `com.vdzon.newsfeedbackend.feed` | Gecureerde feed-items beheren, feedback, cleanup |
| `request` | `com.vdzon.newsfeedbackend.request` | Ad-hoc verzoeken en dagelijkse updates verwerken |
| `podcast` | `com.vdzon.newsfeedbackend.podcast` | Podcast generatie (script + audio) |
| `settings` | `com.vdzon.newsfeedbackend.settings` | Categorie-instellingen en RSS-feed URLs per gebruiker |
| `ai` | `com.vdzon.newsfeedbackend.ai` | Gedeelde OpenAI-client + prijsconfiguratie (gebruikt door rss, feed, request, podcast) |
| `storage` | `com.vdzon.newsfeedbackend.storage` | Gedeelde PostgreSQL/JDBC-opslag-utilities |
| `websocket` | `com.vdzon.newsfeedbackend.websocket` | WebSocket handler voor request-statusupdates; `JwtHandshakeInterceptor` authenticeert de handshake van `/ws/requests` en `broadcast(username, payload)` levert alleen aan de sessies van die gebruiker (SF-2165) |
| `admin` | `com.vdzon.newsfeedbackend.admin` | Gebruikersbeheer en AI-kostenoverzicht (admin-only endpoints) |
| `external_call` | `com.vdzon.newsfeedbackend.external_call` | Logging en kostenberekening van externe API-aanroepen (`external_calls`-tabel); `ExternalCallLogger.logCall(...)` is de gedeelde helper waarmee alle clients loggen (SF-2022) |
| `podcast_source` | `com.vdzon.newsfeedbackend.podcast_source` | Ingest van podcast-RSS-bronnen en episode-verwerking (transcript-lookup); event-driven transcript-fase (`PodcastTranscriptPipeline`) met uurlijks vangnet (`PodcastRecoveryScheduler`); beheer van de feed-lijst incl. validatie van nieuwe feeds en ingestion-trigger achter `PodcastFeedsService` |
| `version` | `com.vdzon.newsfeedbackend.version` | Build-/versie-info endpoint |
| `common` | `com.vdzon.newsfeedbackend.common` | Gedeelde helpers (security, exceptions, Jackson-config, SSRF-URL-validatie) |
| `media` | `com.vdzon.newsfeedbackend.media` | Comprimeert podcast-audio (mono, lage bitrate MP3) zodat bestanden onder Whisper's 25 MB-limiet blijven |
| `search` | `com.vdzon.newsfeedbackend.search` | Tavily-websearch-integratie voor ad-hoc nieuws-verzoeken |
| `shared` | `com.vdzon.newsfeedbackend.shared` | Publieke, read-only gedeelde-feed-endpoints (`/api/shared/feed`, `/api/shared/categories`) voor de reader-app, zonder authenticatie |

### Moduleregels (Spring Modulith)
- Klassen in subpackages van een module zijn **privé** voor die module; alleen klassen direct in de moduleroot (of expliciet gemarkeerd als `@ApplicationModule(type = OPEN)`) zijn van buiten toegankelijk.
- Modules mogen **geen directe imports** doen van interne klassen van andere modules.
- Communicatie tussen modules verloopt via:
  - Directe aanroep van de **publieke service-interface** van de doelmodule
  - Spring Application Events (`ApplicationEventPublisher`) voor losgekoppelde communicatie
- Elk type dat in de **signatuur** van een publieke service-interface voorkomt (parameter of retourwaarde) moet zelf in de moduleroot staan; staat het in `domain/` of `infrastructure/`, dan faalt `ModuleStructureTest` op "depends on non-exposed type". Daarom wonen `AuthToken`, `UserAccount` en (sinds SF-2193) `AuthenticatedUser` naast `AuthService` in `auth/AuthService.kt`. Zo'n resultaat krijgt een **benoemd type** en geen naamloze `Pair`/`Triple`: `validateToken` gaf eerder `Pair<String, String>?` terug, waarbij alleen een KDoc-zin en de destructurering bij de aanroeper vertelden welke helft de gebruikersnaam was.
- Spring Modulith-moduleregels worden afgedwongen door `ModuleStructureTest.kt` (`ApplicationModules.of(Application::class.java).verify()`), met een lege allowlist; deze test draait bij elke `mvn test` en dient als ratchet tegen nieuwe modulegrens-overtredingen (zie §7).

### Packagestructuur per module (voorbeeld: `rss`)
```
com.vdzon.newsfeedbackend.rss/
├── RssService.kt               ← publieke interface (zichtbaar voor andere modules)
├── api/
│   ├── RssController.kt        ← @RestController (@RequestMapping("/api/rss"))
│   └── dto/
│       ├── RssItemResponse.kt  ← response DTO (wat de API teruggeeft)
│       └── FeedbackRequest.kt  ← request DTO
├── domain/
│   ├── RssServiceImpl.kt       ← implementatie (privé)
│   ├── RssItem.kt              ← domeinmodel (privé)
│   └── RssRefreshPipeline.kt   ← pipeline orchestratie (privé)
└── infrastructure/
    ├── RssItemRepository.kt    ← PostgreSQL-opslag (privé)
    └── RssFetcher.kt           ← HTTP RSS-fetch (privé)
```

Dezelfde structuur geldt voor alle andere modules.

---

## 4. Gelaagde Architectuur

Elke module volgt een strikte drielagenstructuur: **API → Domain → Infrastructure**.

### Laag 1: API (Controller)
- Is een handgeschreven Spring `@RestController` met `@RequestMapping` + `@GetMapping`/`@PostMapping`/…; het pad-/methodecontract volgt `openapi.yaml` (niet gegenereerd)
- Ontvangt HTTP-requests met **request DTOs**
- Roept de publieke service-interface aan (nooit rechtstreeks de repository)
- Mapt domeinmodellen naar **response DTOs** vóór teruggave
- Bevat **geen** business logic
- Leest de ingelogde gebruiker via `SecurityHelpers.currentUsername()` (wrapt `SecurityContextHolder`)

### Laag 2: Domain (Service)
- Implementeert de publieke service-interface
- Bevat alle **business logic**
- Werkt intern met **domeinmodellen** (geen DTOs, geen persistentiemodellen)
- Mag andere modules aanroepen via hun publieke service-interface
- Mag `@Async` gebruiken voor achtergrondverwerking
- Mag events publiceren via `ApplicationEventPublisher`

### Laag 3: Infrastructure (Repository / Adapter)
- **Repository:** leest en schrijft naar PostgreSQL (JDBC); geeft domeinmodellen terug
- **Externe adapters:** HTTP-clients voor OpenAI, Tavily, TTS-providers; geven domeinmodellen of primitieven terug
- Bevat geen business logic
- Is volledig privé binnen de module

### DTO-regels

| Context | Type | Locatie |
|---------|------|---------|
| HTTP-request body inkomend | Request DTO (`*Request`, `*Dto`) | `module/api/dto/` |
| HTTP-response body uitgaand | Response DTO (`*Response`) | `module/api/dto/` |
| Interne verwerking tussen lagen | Domeinmodel | `module/domain/` |
| PostgreSQL-opslag | Persistentiemodel (mag gelijk zijn aan domeinmodel) | `module/infrastructure/` |

Domeinmodellen worden **nooit** direct geserialiseerd naar HTTP-responses.

---

## 5. Logging

**Framework:** SLF4J met Logback (standaard via Spring Boot). Gebruik `@Slf4j` (Kotlin: `private val log = LoggerFactory.getLogger(javaClass)`) op elke klasse die logt.

### Logniveaus

| Niveau | Wanneer |
|--------|---------|
| `INFO` | Start/einde scheduled jobs, start/einde verzoekverwerking, statuswijzigingen, gebruikersacties |
| `DEBUG` | Externe API-aanroepen (request-samenvatting), tussenliggende stappen in pipelines |
| `WARN` | Herstelbare fouten: artikel overgeslagen, retry-poging |
| `ERROR` | Niet-herstelbare fouten: job mislukt, externe API onbeschikbaar |

### Verplichte logregels per onderdeel

**Scheduled jobs (INFO):**
```
[RSS] start uurlijkse verwerking voor gebruiker '{username}'
[RSS] stap 1/4: {n} feeds parallel ophalen voor '{username}'
[RSS] {n} nieuwe artikelen voor '{username}'
[RSS] stap 2/4: AI-samenvatting per artikel ({n} stuks)
[RSS]   samengevat {i}/{n}                        (elke 5 items, plus de laatste)
[RSS] stap 3/4: AI-selectie voor de persoonlijke feed ({n} kandidaten)
[RSS]   selectie: {m} van {n} artikelen geselecteerd
[RSS] stap 4/4: uitgebreide feed-samenvattingen genereren ({m} stuks)
[RSS]   feed-item {i}/{m}: {title-truncated-80}    (per item, voor lange AI-calls)
[RSS] klaar: {n} nieuwe artikelen, {m} in feed, duur {s}s
[Summary] dagelijkse samenvatting aangemaakt voor '{username}'
```

Deze stap-voor-stap progress-logs zijn essentieel voor de gebruiker tijdens een lange run — met API-keys duurt stap 2 al gauw enkele minuten bij ~50 items, en stap 4 een paar seconden per geselecteerd item. Zonder deze logs is er geen feedback tussen de start- en klaar-regels in.

**Externe API-aanroepen (DEBUG):**
```
[OpenAI] Aanroep '{operationNaam}' voor gebruiker '{username}' — {n} tokens
  [Tavily] Zoeken op '{query}' — {n} resultaten
[TTS] Audio segment gegenereerd: {n} tekens → {m}ms
```

**Fouten (ERROR):**
```
[RSS] Verwerking mislukt voor gebruiker '{username}': {exception.message}
[Podcast] Generatie mislukt (id={id}): {exception.message}
```

### MDC (Mapped Diagnostic Context)
Stel bij het begin van elke verwerking in:
- `username` — huidige gebruiker
- `requestId` — ID van het verzoek (indien van toepassing)

Dit zorgt dat alle logregels binnen één verwerking de gebruiker en het verzoek bevatten.

### Logformaat
Standaard Spring Boot Logback met leesbare console-tekstopmaak. Er is op dit moment
geen aparte `application-dev.properties` en geen `logstash-logback-encoder` in de
build; productie-logs worden in OpenShift via `oc logs` bekeken.

---

## 6. Monitoring (Grafana)

### Stack
```
Backend (Spring Boot)
  └── Micrometer → /actuator/prometheus
        └── Prometheus (scrapet elke 15s)
              └── Grafana (dashboards)
```

### Dependencies
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### Endpoints
| Endpoint | Doel | Authenticatie |
|----------|------|---------------|
| `GET /actuator/health` | Health check | Geen |
| `GET /actuator/prometheus` | Prometheus-metrics | Geen (intern netwerk) |
| `GET /actuator/info` | Buildinfo | Geen |

Zet in `application.properties`:
```properties
management.endpoints.web.exposure.include=health,prometheus,info
management.endpoint.health.show-details=always
management.prometheus.metrics.export.enabled=true
```

### Custom Metrics (Micrometer)

De daadwerkelijk geregistreerde meters (via `MeterRegistry`):

| Metriek | Type | Labels | Beschrijving |
|---------|------|--------|--------------|
| `newsfeed.rss.fetch.duration` | Timer | `username` | Duur RSS-verwerkingspipeline |
| `newsfeed.rss.items.processed` | Counter | `username` | Artikelen verwerkt |
| `newsfeed.rss.items.in.feed` | Counter | `username` | Artikelen geselecteerd voor feed |
| `newsfeed.podcast.generated` | Counter | `ttsProvider`, `status` | Podcasts gegenereerd |
| `newsfeed.podcast.duration` | Timer | — | Generatieduur podcast |
| `newsfeed.podcast.translated` | Counter | `status` | Vertaalde RSS-podcasts |
| `newsfeed.podcast.translate.duration` | Timer | — | Duur podcast-vertaalpipeline |
| `newsfeed.requests.processed` | Counter | `type`, `status` | Verzoeken afgerond |

> AI-call-kosten en aantallen worden niet als aparte Micrometer-metric bijgehouden,
> maar in de tabel `external_calls` (per-call kostenlog), opvraagbaar via de admin-costs-endpoints.

### Docker Compose voor lokale monitoring
Lever een `docker-compose-monitoring.yml` mee in de backendmap:

```yaml
services:
  prometheus:
    image: prom/prometheus
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana
    ports:
      - "3001:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - ./monitoring/grafana/provisioning:/etc/grafana/provisioning
```

`monitoring/prometheus.yml` scrapet `host.docker.internal:8080`.

Grafana dashboard (JSON-provisioning) toont minimaal:
- RSS items/uur per gebruiker
- AI-aanroepen per minuut per operatie
- AI-kosten per dag
- Podcast-generatietijd
- Error rate (5xx responses)
- JVM heap en GC metrics

---

## 7. Tests

### Huidige testsuite
De automatische tests draaien met `mvn test` (surefire; JUnit 5 / Kotlin), en
sluiten de e2e-suite uit (`**/e2e/**`). De suite in
`src/test/kotlin/com/vdzon/newsfeedbackend/` bestaat uit gerichte unit-tests:

- `rss/RssFetcherImageUrlTest.kt` — extractie van de afbeeldings-URL uit RSS
- `rss/RssFetcherSsrfTest.kt` — SSRF-defense-in-depth-check vlak vóór `http.send(...)` in `RssFetcher.fetch()` (SF-1345)
- `rss/ArticleFetcherSsrfTest.kt` — SSRF-check op de artikel-URL uit een feed-item vlak vóór `http.send(...)` in `ArticleFetcher.fetchPlainText()`; loopback, RFC1918, niet-http-scheme en het link-local metadata-endpoint leveren `null` op met één `ExternalCall` (`status="error"`, `units=0`) (SF-1843)
- `ai/AiJsonTest.kt` — JSON-hulpfuncties voor AI-responses
- `ai/AiPricingPropertiesTest.kt` — OpenAI-prijsconfiguratie (`app.ai.pricing`)
- `api/dto/ApiRequestDtoContractTest.kt` — contract van de request-DTO's
- `podcast/domain/PodcastScriptParserTest.kt` — parser van INTERVIEWER/GAST-scripts
- `common/SsrfUrlValidatorTest.kt` — scheme-afwijzing, elke geblokkeerde IP-range-categorie, geldige publieke URL, niet-resolvebare host (SF-1345)
- `settings/domain/SettingsServiceImplSaveRssFeedsTest.kt` — `saveRssFeeds` wijst ongeldige/SSRF-risicovolle feed-URLs af vóór opslag (SF-1345)
- `podcast_source/PodcastFeedFetcherSsrfTest.kt` — SSRF-defense-in-depth-check vlak vóór `http.send(...)` in `PodcastFeedFetcher.fetch()` (SF-1387)
- `podcast_source/PodcastAudioDownloaderSsrfTest.kt` — SSRF-check op de audio-/enclosure-URL uit een podcast-aflevering vlak vóór `http.send(...)` in `PodcastAudioDownloader.download()`; loopback, RFC1918, niet-http-scheme en het link-local metadata-endpoint leveren `null` op met één `ExternalCall` (`status="error"`, `units=0`) (SF-1877)
- `settings/domain/SettingsServiceImplSavePodcastFeedsTest.kt` — `savePodcastFeeds` wijst ongeldige/SSRF-risicovolle feed-URLs af vóór opslag (SF-1387)
- `podcast_source/domain/PodcastFeedsServiceImplTest.kt` — `savePodcastFeeds` fetcht alleen nieuwe, niet-blanco URLs (bestaande en blanco worden overgeslagen), slaat op vóór het triggeren van de ingestion, en gooit bij een mislukte fetch een `BadRequestException` met de melding `Kon feed niet ophalen: <url> (<reden>|onbekende fout)` zonder op te slaan of te triggeren; gemockte `SettingsService`/`PodcastIngestionTrigger`/`PodcastFeedFetcher` (SF-1683)
- `podcast/domain/PodcastTranslationServiceImplTest.kt` — guard-clauses en idempotency van `startTranslation` (episode niet gevonden → `NotFoundException`/404 sinds SF-2094, episode-status ≠ DONE en leeg transcript → `ConflictException`/409, bestaande vertaling met status ≠/== FAILED, happy path) en `lookup` (episode niet gevonden, met/zonder bestaande vertaling), gemockte `PodcastRepository`/`PodcastEpisodeLookup`/`PodcastTranslator` (SF-1466/SF-1467)
- `podcast_source/domain/PodcastTranscriptPipelineTest.kt` — de event-driven transcript-fase: een `PodcastTranscriptRequested`-event start de verwerking, rate-limit schrijft `retry_count++` + `next_attempt_at` volgens de backoff-tabel (5m/15m/45m/24h), een aflevering in de backoff-wachtkamer wordt niet opgepakt, dubbele events leiden niet tot dubbele verwerking, een verdwenen aflevering en een exception uit de processor laten de pipeline niet omvallen, en er wordt nooit meer dan één aflevering tegelijk verwerkt (SF-1739)
- `shared/api/dto/SharedCategoryDtoTest.kt` — `CategorySettings.toSharedDto()` neemt uitsluitend `id`, `name` en `enabled` over, en de Jackson-serialisatie van `SharedCategoryDto` bevat precies die drie velden: de privé `extraInstructions` (bijstuurtekst voor het taalmodel) en het interne `isSystem` van de bron-gebruiker komen niet in het publieke `GET /api/shared/categories` terecht (SF-1992)
- `external_call/ExternalCallLoggerTest.kt` — de gedeelde `ExternalCallLogger.logCall(...)`-default-implementatie: `id` (UUID), `endTime` en `durationMs` worden zelf ingevuld, de optionele parameters blijven leeg als ze niet worden meegegeven, `subject` wordt níet afgekapt (dat blijft bij de aanroeper), en een `log(call)` die een exception gooit wordt ingeslikt zodat de business-flow doorloopt (SF-2022)
- `request/domain/RequestServiceImplCancelTest.kt` — de eigenaarscheck in `RequestServiceImpl.cancel`: annuleren van een eigen lopend verzoek zet `CANCELLED` plus de annuleervlag, een andere gebruiker of een onbekend id levert `false` op zonder sleutel in de `cancellation`-map en zonder `upsert` (de vlag van de eigenaar blijft dus uit), hetzelfde request-id bij twee gebruikers raakt elkaar niet, een al afgerond eigen verzoek wordt niet overschreven maar geeft wel `true`, en `rerun` ruimt de vlag van dezelfde gebruiker op; gemockte `RequestRepository`/`AuthService`/`RequestWebSocketHandler`/`ApplicationEventPublisher` (SF-2051)
- `websocket/JwtHandshakeInterceptorTest.kt` — de handshake-authenticatie van `/ws/requests` (6 tests): een geldig token laat `beforeHandshake` `true` teruggeven en zet de gebruikersnaam in `attributes["username"]`; een ontbrekende, lege, onzin-, met een ander secret gesmede of verlopen `token`-queryparameter levert `false` op met responsestatus `401` en zonder attribuut. Draait tegen een echte `JwtService` (geen mock), zodat de handtekening- en vervalcontrole echt meeloopt (SF-2166)
- `websocket/RequestWebSocketHandlerTest.kt` — het eigenaarsfilter in `RequestWebSocketHandler.broadcast(username, payload)` (7 tests): een statusbericht gaat alleen naar de sessies van de eigenaar, het `serverVersion`-bericht alleen naar de verbindende sessie, een sessie zónder `username`-attribuut ontvangt niets, een gesloten sessie blokkeert de levering aan de andere niet en wordt opgeruimd — óók als die dode sessie van een ándere gebruiker is (de `isOpen`-check staat bewust vóór het eigenaarsfilter), een sessie die bij het sturen een exception gooit wordt opgeruimd zonder de rest te raken, en na `afterConnectionClosed` ontvangt de sessie niets meer (SF-2166)
- `auth/domain/AuthServiceImplRegisterTest.kt` — de allowlist op de gebruikersnaam bij registratie (10 tests): een gewone naam (`robbert`, `user-a1b2c3d4`) slaagt, en een lege naam, een naam van minder dan 3 of meer dan 64 tekens, een naam met een `/`, met `..` of met een regeleinde levert een `BadRequestException`/400 op. Twee tests pinnen bovendien de *volgorde* van de checks vast: een ongeldige naam geeft `400` en nooit `409` omdat de validatie vóór de duplicaatcontrole staat (`verifyNoInteractions(users)`, dus `findByUsername` wordt niet eens bereikt), en een te kort wachtwoord wint van een ongeldige naam omdat de wachtwoordcheck er nog vóór staat. Elke afwijzing asserteert ook de melding, die de afgewezen naam bewust niet echoot. Gemockte `UserRepository`/`JwtService`/`ApplicationEventPublisher` in de stijl van `SettingsServiceImplSaveRssFeedsTest` (SF-2208)
- `admin/domain/AdminServiceImplDeleteAudioDirTest.kt` — de containment-check in `AdminServiceImpl.deleteAudioDir` (2 tests). Die methode is `private`, dus de tests lopen er via `deleteUser(target, actor)` naartoe met een gemockte `AuthService` waarvan `deleteUser` `true` teruggeeft. Met `@TempDir` als `app.data-dir` en een bestand náást die datadirectory: een traversal-naam (`../../outside`) verwijdert daar niets, terwijl het account zélf nog steeds wordt verwijderd (`verify(auth).deleteUser(...)`); een geldige naam ruimt `<dataDir>/users/<naam>/audio` nog wél volledig op, inclusief geneste bestanden, en laat `<dataDir>/users/<naam>` staan. Let op bij uitbreiden: `dataDir` hoeft niet te bestaan (dat is precies waarom de check `normalize()` gebruikt en géén `toRealPath()`), dus maak in een nieuwe test alleen aan wat je assertie nodig heeft (SF-2208)
- `podcast_source/domain/PodcastRecoverySchedulerTest.kt` — het uurlijkse vangnet: afleveringen met verlopen `next_attempt_at` worden hertriggerd, zonder achterstand publiceert de job niets, hoogstens `MAX_EPISODES_PER_RUN` per run, show-notes-timeout zet de `feed_promotion_attempted_at`-marker vóór het promotie-event (en een mislukte marker blokkeert het event — anti-loop), afleveringen zonder `rss_item_id` worden niet gepromoot, de promotie-timeout komt uit de property, en een fout in de transcript-stap blokkeert de promotie-stap niet (SF-1739)

Daarnaast draait bij elke `mvn test` ook `ModuleStructureTest.kt` —
`ApplicationModules.of(Application::class.java).verify()` met een lege
allowlist — die bewaakt de Spring Modulith-modulegrenzen als ratchet (nieuwe
overtredingen laten de build falen; bestaande overtredingen zijn niet
opgenomen in de allowlist, zie §3).

### E2e-testsuite (`mvn verify`)
Naast de unit-tests bestaat er een e2e-suite onder
`src/test/kotlin/com/vdzon/newsfeedbackend/e2e/` (13 testklassen): `RssRefreshE2eTest`,
`RssItemsE2eTest`, `SettingsE2eTest`, `AdminE2eTest`,
`AuthE2eTest`, `FeedE2eTest`, `FixedRequestsE2eTest`, `PodcastGenerationE2eTest`,
`PodcastIngestE2eTest`, `RequestRecoveryE2eTest`, `RequestsE2eTest`,
`RequestWebSocketE2eTest` en `SharedFeedE2eTest`. Naast de testklassen staat in dezelfde map de hulpklasse
`WsTestClient` (zie hieronder). Het gedeelde
harnas (`E2eTestBase`/`E2eTestConfig`) start
de volledige Spring-app tegen een echte PostgreSQL via Testcontainers (met
echte Flyway-migraties); alleen de externe diensten zijn gefaked
(`FakeOpenAiChatClient`, `FakeContentServer`).

`E2eTestBase` zet `app.podcast.recovery.cron` op `-`
(`Scheduled.CRON_DISABLED`), zodat de podcast-recovery-job niet meeloopt en de
transcript-fase in tests expliciet gescript is. `PodcastIngestE2eTest` gebruikt
dat: één test bewijst dat fase 2 bij `transcribeEnabled=true` puur op het
`PodcastTranscriptRequested`-event start (zonder scheduler), een andere roept
`PodcastRecoveryScheduler.recover()` handmatig aan om een door een restart
gemist event alsnog opgepakt te zien worden (SF-1739).

`RssItemsE2eTest` (9 tests, SF-1754) dekt de losse RSS-item-endpoints en de
reselect-flow: `read`/`unread`, `star` togglen, `feedback`, `markAllRead` (telt
alleen de vooraf ongelezen items en is idempotent), `cleanup` met de
keep-vlaggen, `DELETE /api/rss/{id}`, `GET /api/rss/{id}/transcript` (404 voor
een artikel, 200 met de tekst van een gekoppelde `PodcastEpisode`) en twee
reselect-tests. Items worden rechtstreeks geseed via `RssService.upsert(...)` en
podcast-afleveringen via `PodcastEpisodeRepository.upsert(...)`; alleen de
reselect-tests draaien eerst een echte refresh tegen `FakeContentServer`. Twee
aandachtspunten voor wie deze tests uitbreidt: geseede item-id's moeten
UUID-vorm hebben (`FakeOpenAiChatClient.extractCandidateIds` vist kandidaten met
een UUID-regex uit de selectie-prompt, anders doet reselect stil niets), en een
reselect mag pas getriggerd worden nadat de refresh `DONE` is — `RssRefreshPipeline`
gebruikt één `tryLock` per user en slaat een overlappende run stilzwijgend over.

`FixedRequestsE2eTest` (4 tests, SF-2044) dekt de twee vaste verzoeken die
`UserRegisteredListener` bij registratie aanmaakt (`hourly-update-<user>` en
`daily-summary-<user>`) en wat een handmatige `POST /api/requests/{id}/rerun`
daarmee doet: (1) de dagelijkse samenvatting levert precies één feed-item
`daily-summary-feed-<vandaag>` met `isSummary = true` op en zet het verzoek
terug op `DONE` met `newItemCount = 1`; (2) de twee tijdvensters uit
`RssScheduler` (feed-items van 24 uur, rss-items van 7 dagen) — oudere items
komen niet in de prompt; (3) twee reruns op dezelfde dag houden één feed-item
over, met de inhoud van de laatste run; (4) de routering in
`FixedRequestRerunListener` is exclusief — het uurlijkse verzoek start alleen
de RSS-refresh, het dagelijkse alleen de samenvatting — en de guard in
`AdhocOrchestrator` houdt (nul `tavily_search`- en `adhoc_summarize`-rijen via
`ExternalCallQuery`). Aandachtspunten voor wie deze tests uitbreidt: een rerun
geeft 404 zolang `UserRegisteredListener` de vaste verzoeken nog niet heeft
aangemaakt, dus daar moet eerst op gewacht worden; `newItemCount` wordt door
`rerun` eerst op 0 gezet en is daarmee het natuurlijke `await`-anker; en het
samenvattings-item van een eerdere run valt zelf binnen het 24-uursvenster van
een volgende run, daarom staat de vensterassertie in een eigen test met precies
één run.

`RequestRecoveryE2eTest` (5 tests, SF-2158) dekt het opstartherstel uit §6.6 van
`backend-functional-spec.md`: `RequestServiceImpl.resetStuck()`, dat bij
serverstart door `RequestBootstrap` wordt aangeroepen. De vijf gevallen: (1) een
`PROCESSING`-verzoek wordt `FAILED` met een `completedAt` die van de reset komt
(de beginsituatie zonder `completedAt` wordt eerst via HTTP vastgelegd, zodat de
test bewijst dát `resetStuck` de waarde zette); (2) ook een verzoek dat nog in
`PENDING` stond wordt `FAILED`; (3) een `DONE`- en een `CANCELLED`-verzoek
houden hun status én hun oorspronkelijke `completedAt`, net als de twee vaste
verzoeken (`hourly-update-<user>`/`daily-summary-<user>`, die op `DONE` staan met
een lege `completedAt`); (4) de reset raakt álle gebruikers, bewezen met een
tweede geregistreerde gebruiker; (5) een tweede `resetStuck()` direct erna
verandert niets — de `completedAt` van de eerste reset blijft staan.
Aandachtspunten voor wie deze tests uitbreidt: `resetStuck()` wordt rechtstreeks
op de `@Autowired RequestService`-bean aangeroepen (de app wordt niet herstart;
`RequestBootstrap` doet niets anders dan deze aanroep plus `ensureFixedRequests`)
en loopt via `auth.listUsernames()` over álle gebruikers in de database — dus ook
over die van eerder gedraaide tests in dezelfde fork. Asserteer daarom nooit op de
totale lijst of het totale aantal verzoeken, maar altijd per eigen gebruiker en
per eigen request-id, en lees direct na de reset (de hardcoded
`RssScheduler`-cron `0 0 * * * *` staat, anders dan `app.podcast.recovery.cron`,
niet uit en kan bij een run over het hele uur het `hourly-update-*`-verzoek
aanraken). Begintoestanden worden uitsluitend met `requestService.upsert(...)` en
UUID-vormige id's gezet — een écht lopend verzoek dat op een latch in
`PROCESSING` wacht zou door de orkestrator alsnog op `DONE` gezet kunnen worden
terwijl de test asserteert. Elke test wacht na `registerUser(...)` eerst tot
`GET /api/requests` de twee vaste verzoeken toont (patroon uit
`FixedRequestsE2eTest`): `ensureFixedRequests` eindigt in een `repo.save(...)`
die eerst alle rijen van de gebruiker vervangt, dus zonder dat anker kan die
asynchrone stap een net geseed verzoek weer wegvagen. Alle asserties op `status`
en `completedAt` komen uit de respons van `GET /api/requests` met het token van
de betreffende gebruiker, niet uit de repository, zodat de serialisatie meeloopt.

`RequestsE2eTest` dekt sinds SF-2051 ook de eigenaarsgrens rond annuleren: een
tweede gebruiker die het id van een lopend verzoek kent krijgt `404`, het
verzoek van de eigenaar blijft `PROCESSING` en loopt gewoon door naar `DONE`, en
`POST /api/requests/{onbekend-id}/cancel` geeft `404` in plaats van de vroegere
`204`. Aandachtspunten voor wie die tests uitbreidt: de assertie "er blijft geen
annuleervlag achter" injecteert `RequestServiceImpl` concreet met `@Autowired`
(de `cancellation`-map staat niet op de `RequestService`-interface) en leunt op
de huidige sleutelvorm `"$username/$id"` — verandert die vorm, dan wordt die
assertie stil triviaal-waar; en de aanvalspoging moet plaatsvinden terwijl de
eigenaar écht nog verwerkt, waarvoor de test de eerste `adhoc_summarize` van
`FakeOpenAiChatClient` met een `CountDownLatch` vasthoudt.

`RequestWebSocketE2eTest` (6 tests, SF-2109/SF-2166) dekt het WebSocket-endpoint
`/ws/requests` zoals §5 van `backend-functional-spec.md` en de sectie
*WebSocket-integratie* van `frontend-spec.md` het beschrijven — de REST-kant van
`NewsRequest` was al via `RequestsE2eTest` gedekt, deze kant niet: (1) na connect
komt precies één `serverVersion`-bericht met `sha`/`buildTime` allebei `"unknown"`
(`BUILD_SHA`/`BUILD_TIME` worden alleen in de Docker-image gezet, dus in tests
geldt de gedocumenteerde fallback); (2) een tweede verbinding levert bij de
eerste géén tweede `serverVersion` — het is bewust geen broadcast; (3) bij een
ad-hoc verzoek komen de statuswijzigingen binnen als volledige `NewsRequest`-
objecten zonder `type`-veld, met de reeks `PENDING → PROCESSING → DONE` en de
velden `id`/`status`/`subject`/`newItemCount` erin (er is geen DTO-laag, dus deze
serialisatie wordt nergens anders afgedekt); (4) sinds SF-2166 gaan die
statusberichten alleen naar de eigenaar — een verzoek van gebruiker A levert bij
een gelijktijdig verbonden gebruiker B géén bericht met dat id op, terwijl A de
volledige reeks wél ziet; (5) een handshake zonder `token` en een handshake met
een onzin-token worden allebei geweigerd; (6) na een nette close van de ene
verbinding blijft de andere berichten ontvangen.
Aandachtspunten voor wie deze tests uitbreidt: de verbinding loopt via de
JDK-`HttpClient.newWebSocketBuilder()` (geen extra dependency), verpakt in de
test-only hulpklasse `WsTestClient`. `WsTestClient.connect(port, mapper, token)`
hangt het token als queryparameter aan de URL; `/ws/**` staat weliswaar op
`permitAll`, maar `JwtHandshakeInterceptor` authenticeert de handshake, dus elke
test registreert eerst een gebruiker (`E2eTestBase.TestUser.token`) en verbindt
daarmee. `token = null` is de vorm waarmee de weigering getest wordt: de server
antwoordt met `401`, er komt geen sessie tot stand en de JDK-client uit zich dat
als een falende `buildAsync(...)`-future (`WebSocketHandshakeException` in de
`ExecutionException`) — asserteer daar dus op, niet op een close-code. De klasse
vangt daarnaast twee valkuilen van de JDK-listener af — `onText` moet
zelf `webSocket.request(1)` aanroepen (anders komt na het eerste bericht niets
meer binnen) en levert fragmenten, dus er wordt pas geparsed bij `last == true`.
Verbind altijd vóór het uitlokken van een statuswijziging en consumeer eerst het
`serverVersion`-bericht als anker dat de sessie server-side geregistreerd is; er
staat nergens een vaste `sleep`, wachten gebeurt via `poll(timeout)` en de enige
korte time-out (2 s) zit in de negatieve asserties. Bij de twee-gebruikerstest is
het anker dat A `DONE` al gezien heeft — een ongefilterde broadcast had dan
allang in B's queue gestaan — met daarbovenop een venster van 5 s voor nawerk.
Asserteer per `id`: de
hardcoded `RssScheduler`-cron (`0 0 * * * *`) is niet uit te schakelen, dus een
run precies over het hele uur kan er een `hourly-update-*`-broadcast tussen
zetten. De klasse zet net als `RequestsE2eTest` via een eigen
`@DynamicPropertySource` een dummy `app.tavily.api-key`, anders doet
`TavilyClient` geen HTTP-call naar `FakeContentServer`. Test 6 gebruikt twee
verbindingen van **dezelfde** gebruiker (anders zou het eigenaarsfilter, en niet
de close, de tweede verbinding stil houden) en bewijst alleen het waarneembare
gedrag: bij een nette close haalt Spring de sessie al weg in
`afterConnectionClosed`, dus de dode-sessie-tak in
`RequestWebSocketHandler.broadcast` wordt er niet gegarandeerd door geraakt —
die tak is sinds SF-2166 wel op unit-niveau gedekt door
`RequestWebSocketHandlerTest`.

`mvn test` (surefire) draait alleen de unit-tests en `ModuleStructureTest`
(sluit `**/e2e/**` uit, geen Docker nodig). `mvn verify` (failsafe) draait
daarnaast de e2e-suite — dit vereist Docker (Testcontainers) en start elke
e2e-testklasse in een eigen JVM-fork om state-accumulatie tussen klassen te
voorkomen.

### Startconfiguratie van de test-JVM (`<argLine>`, SF-2151)
Zowel `maven-surefire-plugin` als `maven-failsafe-plugin` zet in `pom.xml`:

```xml
<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>
```

Dit raakt geen applicatiegedrag — alleen de manier waarop de test-JVM start —
maar de drie onderdelen hangen aan elkaar en zijn geen van alle optioneel:

- **`-javaagent:…mockito-core…`** geeft Mockito expliciet als Java-agent mee.
  Zonder dit doet Mockito dynamic self-attach en waarschuwt JDK 21 daarover
  (in een latere JDK wordt het een fout). `-Xshare:off` onderdrukt de
  `Sharing is only supported for boot loader classes`-CDS-regel die het
  toevoegen van een javaagent oproept. Samen brengen ze de zes ruisregels van
  `mvn -B clean test` op nul (`grep -icE 'warning|deprecat|self-attach'` → 0).
- **`@{argLine}`** is de late-evaluatie-referentie naar de property die
  `jacoco:prepare-agent` (bij failsafe: `prepare-agent-integration`) zet. Laat
  je die prefix weg, dan wordt de JaCoCo-agent **stil** overschreven: de build
  blijft groen en er komt geen enkele melding, maar `target/jacoco.exec`
  (~335 KB) en `target/jacoco-it.exec` (~8,5 MB) worden niet meer geschreven en
  het coveragerapport (`target/site/jacoco-it/index.html`) is leeg. Controleer
  na elke wijziging aan een `<argLine>` dus dat die bestanden er nog zijn.
- **`maven-dependency-plugin`, goal `properties`** (zonder expliciete `<phase>`
  → default `initialize`, dus vóór `jacoco:prepare-agent`; zonder `<version>` →
  komt uit `spring-boot-starter-parent`, resolvet naar `dependency:3.9.0`) vult
  de placeholder `${org.mockito:mockito-core:jar}` met het jar-pad. Surefire
  vult zo'n placeholder namelijk alleen zelf in voor *directe* dependencies, en
  `mockito-core` komt hier transitief via `spring-boot-starter-test` binnen.
  Ontbreekt de stap, dan gaat de letterlijke placeholder naar de JVM en crasht
  de fork met `Error opening zip file or JAR manifest missing` /
  `The forked VM terminated without properly saying goodbye`.

### Ongebruikte testtooling (bevinding)
De `pom.xml` bevat nog test-dependencies voor Cucumber (`cucumber-spring`,
`cucumber-junit-platform-engine`) en WireMock (`wiremock-standalone`). Er zijn
geen feature-bestanden, step-definitions of WireMock-stubs in de repo — de
e2e-strategie is in de praktijk vervangen door Testcontainers + de
handgeschreven Fake-clients hierboven. Deze dependencies lijken daarmee
(vermoedelijk) legacy/ongebruikt; het verwijderen ervan is een codewijziging
en valt buiten de scope van deze documentatie-update.

---

## 8. API-contract (`openapi.yaml`)

`specs/openapi.yaml` is het **handmatig onderhouden** contract voor de REST-API en geldt als source of truth voor de interface tussen backend en frontend. Er is **geen** code-generatiestap in de build: `pom.xml` bevat geen `openapi-generator-maven-plugin` en er bestaat geen `target/generated-sources/openapi/`-map.

De controllers zijn met de hand geschreven Spring `@RestController`-klassen (`@RequestMapping` + `@GetMapping`/`@PostMapping`/…) die direct met de bestaande Kotlin-domein-/DTO-modellen werken.

**Werkwijze bij een API-wijziging:** pas zowel `openapi.yaml` als de betreffende controller aan en houd ze consistent (paden, methoden, request/response-vorm). `openapi.yaml` wordt zo de bron die de implementatie beschrijft, niet genereert.

**Drift is stil (SF-2073).** Omdat er geen generatiestap en geen contract-test is, faalt niets als een schema en zijn Kotlin-model uit elkaar lopen. De audit van SF-2073 vond drie soorten drift tegelijk in de podcast-/request-schema's; controleer bij een wijziging daarom veld-vóór-veld tegen de data class, in beide richtingen:

- **Velden die de backend nooit stuurt.** `NewsRequest`, `CategoryResult` en `Podcast` beloofden alle drie een `costUsd`; dat veld bestaat op geen van de drie modellen. AI-kosten worden niet per verzoek of per podcast bijgehouden, maar per externe aanroep in `external_calls` en zijn opvraagbaar via `/api/admin/costs/**` (zie het kader bij §6) — die verwijzing staat nu als schema-`description` bij `NewsRequest` en `Podcast`, zodat de volgende lezer niet denkt dat de informatie verdwenen is.
- **Berekende properties die wél geserialiseerd worden.** Een `val x: Boolean get() = …` in de class-body staat niet in de constructor, maar gaat wel mee in de JSON: `Podcast.isTranslation` (`translatedFromEpisodeGuid != null`, `podcast/PodcastService.kt:65`) ontbrak daardoor jarenlang in het schema. Zulke velden krijgen een `description` die zegt dat het een berekende, alleen-in-responses property is; gebruik géén `readOnly: true` — dat mechanisme komt nergens in `openapi.yaml` voor.
- **Types die strenger beloven dan de implementatie.** `TranslationStart.status` verwees naar het enum-schema `PodcastStatus`, terwijl `TranslationStart.status` een kale `String` is die met `status.name` gevuld wordt (`PodcastTranslationServiceImpl.kt:76`/`:105`). Het staat nu op `type: string` met een `description` die de herkomst benoemt. Een `$ref` naar een enum belooft validatie die de API niet doet. Sinds SF-2172 staat er bij zo'n veld óók een inline `enum` met de waarden van het Kotlin-enum: het type mag dan `string` zijn, de waardenverzameling is wél gesloten (zie het SF-2130-blok hieronder).

**Foutcodes driften net zo stil (SF-2094).** De audit van SF-2094 keek naar de andere helft van het contract — de `responses`-blokken — en vond dezelfde stilte: zes operaties gooiden een `NotFoundException` zonder dat er een `'404'` in `openapi.yaml` stond (`deleteRequest`, `rerunRequest`, `deletePodcast`, `resetUserPassword`, `setUserRole`, `deleteUser`), en één operatie beloofde een `'404'` die de code als 409 teruggaf. Controleer daarom in beide richtingen:

- **Code → contract.** Elke `NotFoundException`-throw-site die vanaf een endpoint bereikbaar is, hoort een `'404'` te hebben op de bijbehorende operatie. Verificatiegrep: `grep -rn "NotFoundException(" src/main` (14 throw-sites + de klassedeclaratie in `common/Exceptions.kt`), af te zetten tegen de 12 operaties met een `'404'` in `openapi.yaml`. Dezelfde regel geldt voor élke statuscode die de code zelf gooit, niet alleen 404: SF-2179 liep hem na voor `BadRequestException`/`'400'`, waar hij nooit was uitgevoerd (12 throw-sites tegenover 3 operaties met een `'400'`), en vulde de zeven ontbrekende blokken aan (`changePassword`, `resetUserPassword`, `setUserRole`, `deleteUser`, `getDailyCosts`, `getCostsByUser`, `getExternalCalls`). Verificatiegrep: `grep -rn "BadRequestException(" src/main` (12 throw-sites + de klassedeclaratie in `common/Exceptions.kt`), af te zetten tegen de 10 operaties met een `'400'` in `openapi.yaml` — de telling is geen één-op-één-verhouding, want twee operaties hebben elk twee throw-sites: `setUserRole` (rolwaarde in `AuthServiceImpl`, eigen adminrol in `AdminServiceImpl`) en `PUT /api/podcast-feeds` (`SettingsServiceImpl` en `PodcastFeedsServiceImpl`).
- **Contract → code.** Elke `'404'` heeft een 404-bron in de code. Dat is meestal een `NotFoundException`, maar niet altijd: `GET /api/rss/{id}/transcript` haalt zijn 404 uit een expliciete `ResponseEntity.notFound()` in `RssController.kt`. Beide vormen zijn toegestaan; een `'404'` zónder bron is dat niet. Hetzelfde voor de andere kant: elk van de tien `'400'`-blokken is sinds SF-2179 herleidbaar tot een `BadRequestException` in `src/main` (zie het SF-2179-blok onderaan deze paragraaf).
- **Bij een afwijking: kies de kant van het contract** zolang die bij het patroon van de buur-endpoints past. `PodcastTranslationServiceImpl.startTranslation` gooide bij een onbekende `episodeGuid` een `ConflictException` (409) terwijl het contract al 404 documenteerde; dat is nu een `NotFoundException`, gelijk aan `lookupByRssItem`. De twee state-conflicten in dezelfde methode (status ≠ `DONE`, leeg transcript) blijven bewust 409 — 409 is hier gereserveerd voor "de aflevering bestaat, maar is er nog niet klaar voor".
- **De `description` draagt de reden.** Een 404 die uit een eigenaarscheck komt (zie de codeconventie in `docs/factory/technical-spec.md`) is een privacykeuze, geen "bestaat niet": die operaties zeggen expliciet "onbekend id, of de/het \<resource\> is van een andere gebruiker", met `<resource>` ingevuld als het concrete type van die operatie ("de podcast", "het verzoek", "de aflevering"). Hetzelfde geldt voor een beleidsweigering — `DELETE /api/requests/{id}` geeft ook 404 voor een vast verzoek (prefix `hourly-update-` / `daily-summary-`), dat mág simpelweg niet weg. Zonder die zinnen leest een client-bouwer alleen "not found". Een `'404'` blijft in dit bestand een kaal blok met alleen een `description`; er is geen gedeelde `components/responses`-component en die wordt hier ook niet geïntroduceerd.

**Voorbeeldwaarden en `enum`'s driften ook (SF-2130).** De derde audit keek naar het auth- en rolmodel en vond drift in de delen van het contract die *niemand parseert*: `example:`-waarden, ontbrekende `enum`'s en de inleidende prozatekst. Vier soorten, alle vier alleen met het oog te vinden:

- **Een veld dat de backend wél stuurt maar het schema niet kent.** `AuthResponse` had alleen `token` en `username`, terwijl `AuthToken` (en dus `POST /api/auth/register` én `/login`) ook `role` teruggeeft. Zo'n gat valt niet op bij een controller-review, want de controller geeft gewoon het hele object terug — vergelijk het schema met de data class, niet met de signature.
- **De constantnaam als voorbeeldwaarde.** `AdminUserView.role` had `example: ROLE_USER` en `SetRoleRequest.role` had `example: ROLE_ADMIN`. In Kotlin zijn dat de námen van de constanten (`auth/domain/User.kt`); de wáárdes zijn `"user"` en `"admin"`. Een client die het voorbeeld letterlijk overnam op `PUT /api/admin/users/{username}/role` kreeg een `400` uit `AuthServiceImpl.setRole`. Een fout `example:` is dus geen cosmetisch detail — het is de enige plek waar een contractlezer de vorm van de waarde vandaan haalt. Verificatie: `grep -n 'example: ROLE_' specs/openapi.yaml` hoort nul treffers te geven.
- **Een gesloten waardenverzameling zonder `enum`.** Alle drie de role-velden (`AuthResponse.role`, `AdminUserView.role`, `SetRoleRequest.role`) staan nu op `enum: [user, admin]`, want de backend produceert en accepteert per constructie niets anders. Let op het verschil met de regel hierboven over `$ref` naar een enum-schema: die gaat over een veld dat in Kotlin géén enum is en dus geen `$ref` verdient — een inline `enum` op een `String`-veld waarvan de code de waarden expliciet afdwingt (hier met een `BadRequestException`) is juist wél de waarheid. "Afdwingen" is daarbij breder dan een validatie op de invoer: een veld dat de backend alleen maar vult met `.name` van een Kotlin-enum is net zo gesloten, en hoort dus dezelfde inline `enum` te krijgen (SF-2172).
- **Prozateksten met een opsomming.** De `info.description` noemde drie publieke paden terwijl `SecurityConfig` er vijf op `permitAll` zet (`/api/shared/**` en `/actuator/**` ontbraken) — terwijl elders in hetzelfde bestand bij de shared-endpoints al `security: []` stond. Elke opsomming in een `description` is een tweede kopie van iets wat in de code staat en veroudert stil mee; bij een wijziging in `SecurityConfig` hoort die zin dus in dezelfde diff.

Wat hier **geen** drift is: de ~20 resterende `ROLE_ADMIN`-voorkomens in tags, endpoint-`summary`s en `403`-`description`s. Dat is de Spring-Security-autoriteitsnaam uit `hasRole("ADMIN")` en die formulering is op die plekken correct. Doe bij zulke correcties dus nooit een brede zoek-en-vervang op `ROLE_`: het onderscheid is precies "naam van de autoriteit" (proza) versus "waarde die over de lijn gaat" (`example:`/`enum`).

**Een huisregel geldt niet met terugwerkende kracht (SF-2172).** SF-2094 en SF-2130 schreven hun regel op en pasten hem toe op de gevallen die de audit toevallig raakte; de rest bleef staan. SF-2172 was de opruimronde daarna en liep beide regels nog eens langs het hele bestand:

- **Inline `enum` op de podcast-statusvelden.** `EpisodeLookup.episodeStatus` (9 waarden van `PodcastEpisodeStatus`, in declaratievolgorde van `podcast_source/PodcastEpisode.kt`), `EpisodeLookup.translatedPodcastStatus` en `TranslationStart.status` (elk de 8 waarden van `PodcastStatus`) hebben nu naast hun `description` een inline `enum`. De waarden voor de laatste twee zijn letterlijk uit het bestaande `PodcastStatus`-schema in hetzelfde bestand overgenomen, zodat de lijsten niet uiteen kunnen lopen. `translatedPodcastStatus` houdt `nullable: true` en krijgt géén `null` in de enum-lijst: het bestand is `openapi: 3.1.0` maar gebruikt overal het 3.0-sleutelwoord `nullable`, en die huisstijl volgen we tot iemand het bestand in zijn geheel opschoont. `Podcast.status` bleef een `$ref` — dat veld is in Kotlin een echt enum.
- **De reden bij vier eigenaarscheck-404's.** `getPodcast`, `getPodcastAudio`, `lookupEpisodeByRssItem` en `translatePodcastEpisode` zeiden alleen "Niet gevonden" / "Audio nog niet beschikbaar" en noemen nu het concrete resourcetype plus de eigenaarsreden. Nieuw detail dat hieruit volgt: heeft één `'404'` **twee** bronnen, dan hoort de `description` ze allebei te benoemen — bij `GET /api/podcasts/{id}/audio` zijn dat de per-gebruiker gefilterde podcast-lookup én het ontbrekende MP3-bestand (`PodcastController.kt`, twee gefilterde lookups). Alleen de eigenaarsreden opschrijven zou de bestaande betekenis "audio nog niet klaar" hebben weggepoetst.
- **Een prozalijst is ook contract.** De `description` van `createPodcast` schreef het statusverloop als `… → GENERATING_AUDIO → DONE` en miste `FAILED`, terwijl het `PodcastStatus`-schema in hetzelfde bestand en `podcast/PodcastService.kt` allebei `DONE/FAILED` zeggen; dat is nu gelijkgetrokken. Zulke opsommingen staan los van elk schema en worden door niets gecontroleerd — grep bij een statuswijziging dus ook op de statuswaarden in `description`-teksten, niet alleen op de `enum`-lijsten.

Praktisch gevolg voor de volgende audit: schrijf de regel niet alleen op, maar grep hem meteen repo-breed na. Voor deze twee: `grep -n "type: string" -A2 specs/openapi.yaml` langs elk veld waarvan de `description` een enum-naam noemt, en `grep -n "'404':" -A2 specs/openapi.yaml` langs elke operatie met een per-gebruiker gefilterde lookup.

**De regel gold maar voor één statuscode (SF-2179, stand bijgewerkt in SF-2187).** SF-2094 schreef "Code → contract" op als een regel over elke throw-site, maar liep hem alleen na voor `NotFoundException`/`'404'`. Voor `BadRequestException`/`'400'` was hij nooit uitgevoerd: 12 throw-sites tegenover 3 operaties met een `'400'`. SF-2179 vulde de zeven ontbrekende blokken aan (`changePassword`, `resetUserPassword`, `setUserRole`, `deleteUser`, `getDailyCosts`, `getCostsByUser`, `getExternalCalls`); SF-2187 deed daarna hetzelfde voor `UnauthorizedException`/`'401'`, waar `PUT /api/account/password` twee throw-sites had zonder `'401'` in het contract.

De stand van zaken is daarmee: 404, 400, 409 én 401 zijn nagelopen. Per statuscode de verificatiegrep en de telling — het regelaantal van de grep is steeds de throw-sites plús de klassedeclaratie in `common/Exceptions.kt`, dus één regel meer dan er throw-sites zijn:

- `grep -rn "NotFoundException(" newsfeedbackend/newsfeedbackend/src/main` = 15 regels (14 throw-sites + de klassedeclaratie) tegenover `grep -c "'404'" specs/openapi.yaml` = 12 (nagelopen in SF-2094).
- `grep -rn "BadRequestException(" newsfeedbackend/newsfeedbackend/src/main` = 14 regels (13 throw-sites + de klassedeclaratie) tegenover `grep -c "'400'" specs/openapi.yaml` = 10 (nagelopen in SF-2179; de dertiende throw-site kwam er in SF-2208 bij op `register`, zie het blok hieronder — het aantal `'400'`-operaties bleef daarbij gelijk).
- `grep -rn "ConflictException(" newsfeedbackend/newsfeedbackend/src/main` = 4 regels (3 throw-sites + de klassedeclaratie) tegenover `grep -c "'409'" specs/openapi.yaml` = 2 — die klopte al en had daarom geen eigen story nodig.
- `grep -rn "UnauthorizedException(" newsfeedbackend/newsfeedbackend/src/main` = 6 regels (5 throw-sites + de klassedeclaratie) tegenover `grep -c "'401'" specs/openapi.yaml` = 2 ná SF-2187. Twee throw-sites horen bij `login` (`AuthServiceImpl.kt:43`/`:44`, al gedekt), twee bij `changePassword` (`:53`/`:55`, het gat dat SF-2187 dichtte). `SecurityHelpers.kt:8` (`"not authenticated"`) is de expliciete uitzondering: die 401 hoort bij élk beveiligd endpoint en wordt door het globale `security`-blok afgedekt, niet per operatie gedocumenteerd.

Wat daaruit volgt, geldt voor elke statuscode die hierna aan de beurt is:

- **De `description` van een `'400'` benoemt de concrete oorzaak, nooit alleen "Bad Request".** Precies zoals een `'404'` de reden draagt (zie het SF-2094-blok hierboven), zegt een `'400'` wélke invoer geweigerd wordt: "Nieuw wachtwoord te kort (minimaal 4 tekens)", "`days` valt buiten 1..365", "Onbekende `period`; toegestaan zijn `this_month`, `last_month`, `this_year` en `all`". Zonder die zin weet een client-bouwer niet welk veld hij moet valideren.
- **Is de reden een gebruikerszichtbare Nederlandse melding, neem die dan letterlijk over.** `setUserRole` en `deleteUser` weigeren dat een admin zijn eigen adminrol intrekt ("Je kunt je eigen admin-rol niet verwijderen") of zichzelf verwijdert ("Je kunt jezelf niet verwijderen"), beide uit `admin/domain/AdminServiceImpl.kt`. Die tekst komt één-op-één in de app terecht, dus hij hoort in het contract — een client moet er een scherm voor maken. De overige `'400'`-descriptions zijn Nederlands ook waar de onderliggende Kotlin-melding Engels is; dat volgt de taal van het bestand.
- **Meer dan één bron: benoem ze allemaal** — dezelfde regel als bij de 404 van `GET /api/podcasts/{id}/audio` (SF-2172). `setUserRole` heeft er twee (ongeldige rolwaarde in `AuthServiceImpl`, eigen adminrol in `AdminServiceImpl`) en beide staan in de `description`.
- **Een `minimum`/`maximum` op een parameter vervangt de `'400'` niet.** `GET /api/admin/costs/daily` had al `minimum: 1` / `maximum: 365` op `days`, maar Spring valideert die schema-grenzen niet: `AdminCostsController` doet de check zelf en gooit een `BadRequestException`. De constraint blijft staan, de `'400'` komt erbij — een parameter-constraint is documentatie, geen afdwinging.
- **De telling is nooit één-op-één.** 13 throw-sites tegenover 10 operaties met een `'400'`, want drie operaties hebben er elk twee: `setUserRole` (rolwaarde in `AuthServiceImpl`, eigen adminrol in `AdminServiceImpl`), `PUT /api/podcast-feeds` (`SettingsServiceImpl` én `PodcastFeedsServiceImpl`) en sinds SF-2208 `POST /api/auth/register` (wachtwoordlengte én gebruikersnaam). Verwacht bij zo'n audit dus geen gelijke getallen; herleid elke throw-site afzonderlijk naar zijn operatie. Verificatiegrep: `grep -rn "BadRequestException(" newsfeedbackend/newsfeedbackend/src/main` (14 regels = 13 throw-sites + de klassedeclaratie in `common/Exceptions.kt`) tegenover `grep -c "'400'" specs/openapi.yaml` (10).

**Een tweede reden voor een bestaande `'400'` is een `description`-wijziging, geen nieuwe responsesleutel (SF-2208).** De username-allowlist op `POST /api/auth/register` kwam op een operatie die al een `'400'` had ("Wachtwoord te kort (minimaal 4 tekens)"). De juiste zet is dan die ene `description` te verbreden — hij noemt nu beide oorzaken, conform de regel "meer dan één bron: benoem ze allemaal" hierboven — en niet een tweede `'400'`-blok toe te voegen (dat kan in YAML ook niet: het is één sleutel per statuscode). Praktisch gevolg voor de audit: een operatie die al in de `'400'`-telling zit, valt bij de volgende ronde niet op als "ontbrekend", terwijl de reden wél is uitgebreid. De telling van `'400'`-blokken bewaakt dus alleen de *aanwezigheid* van de statuscode; of de `description` nog volledig is, blijft leeswerk per operatie.

Twee dingen die hier bovendien voor het eerst in het contract staan en die bij een volgende invoervalidatie hetzelfde patroon volgen:

- **Een invoerregel die de code afdwingt hoort óók als schema-constraint op het request-veld.** `AuthRequest.username` draagt sinds SF-2208 `pattern: '^[A-Za-z0-9._-]{3,64}$'` plus `minLength: 3`/`maxLength: 64` — exact dezelfde grenzen als `AuthServiceImpl.USERNAME_PATTERN`. Net als bij `minLength: 4` op de wachtwoordvelden (SF-2187) dwingt Spring die constraint níét af (er is geen `@Valid`, zie de codeconventie over invoervalidatie in `docs/factory/technical-spec.md`) — het is documentatie naast de `'400'`, niet in plaats daarvan. Wijzigt de regex in de code, dan hoort het schema in dezelfde diff.
- **Een validatie die maar op één van meerdere endpoints geldt, zegt dat expliciet.** De allowlist geldt alleen op `register`, niet op `login` of `changePassword` — bestaande accounts met een oude naam moeten kunnen blijven inloggen. `AuthRequest` wordt door zowel `register` als `login` gebruikt, dus de constraint staat op een schema dat óók door een operatie zonder die validatie wordt gedeeld; die asymmetrie staat in `specs/backend-functional-spec.md` §3 en niet in het schema, omdat OpenAPI hem daar niet uit kan drukken. Kom je zo'n gedeeld request-schema tegen, controleer dan of de strengste operatie het schema onbedoeld voor de andere aanscherpt.

Ook hier blijft de vorm gelijk: een `'400'` is een kaal blok met alleen een `description`, direct na `'200'`/`'201'` en vóór `'403'`/`'404'`; er komt geen gedeelde `components/responses` (`grep -n 'components/responses' specs/openapi.yaml` hoort leeg te blijven). Niets in de build parseert dit bestand, dus de controle is en blijft grep- en leeswerk.
