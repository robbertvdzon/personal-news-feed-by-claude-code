# Personal News Feed — Backend Technische Specificatie

> **Doel van dit document:** Architectuur, codestructuur, patterns, monitoring en testing van de backend. Voor functioneel gedrag: zie [`backend-functional-spec.md`](./backend-functional-spec.md).

---

## 1. Tech Stack & Dependencies

**Taal/platform:** Spring Boot **4.x**, Kotlin **2.x**, poort 8080.

**Build tool:** Maven (`pom.xml`).

**Belangrijke dependencies:**

| Dependency | Doel |
|-----------|------|
| `com.fasterxml.jackson.module:jackson-module-kotlin` + `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | JSON-serialisatie (Kotlin-support + Java-tijd) — `pom.xml` en alle imports gebruiken de `com.fasterxml.jackson`-groupId |
| `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` | JWT aanmaken en valideren |
| `com.rometools:rome` | RSS 2.0 en Atom feeds parsen |
| `spring-boot-starter-websocket` | WebSocket support — **niet** WebFlux |
| `spring-boot-starter-security` | Spring Security voor JWT-filter en CORS |
| `spring-boot-starter-actuator` | Health en metrics endpoints |
| `io.micrometer:micrometer-registry-prometheus` | Prometheus metrics export |
| `org.springframework.modulith:spring-modulith-starter-core` | Modulaire monolith structuur en verificatie |

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
| `auth` | `com.vdzon.newsfeedbackend.auth` | Registratie, login, JWT-aanmaak en -validatie, gebruikersbeheer |
| `rss` | `com.vdzon.newsfeedbackend.rss` | RSS-feeds ophalen, AI-verwerking van artikelen, feed-selectie |
| `feed` | `com.vdzon.newsfeedbackend.feed` | Gecureerde feed-items beheren, feedback, cleanup |
| `request` | `com.vdzon.newsfeedbackend.request` | Ad-hoc verzoeken en dagelijkse updates verwerken |
| `podcast` | `com.vdzon.newsfeedbackend.podcast` | Podcast generatie (script + audio) |
| `settings` | `com.vdzon.newsfeedbackend.settings` | Categorie-instellingen, RSS-feed URLs, event-voorkeuren en -denylist per gebruiker |
| `events` | `com.vdzon.newsfeedbackend.events` | Tech-events ontdekken, video's en beheren (incl. verwijderen) |
| `ai` | `com.vdzon.newsfeedbackend.ai` | Gedeelde OpenAI-client + prijsconfiguratie (gebruikt door rss, feed, request, podcast, events) |
| `storage` | `com.vdzon.newsfeedbackend.storage` | Gedeelde PostgreSQL/JDBC-opslag-utilities |
| `websocket` | `com.vdzon.newsfeedbackend.websocket` | WebSocket handler voor request-statusupdates |
| `admin` | `com.vdzon.newsfeedbackend.admin` | Gebruikersbeheer en AI-kostenoverzicht (admin-only endpoints) |
| `external_call` | `com.vdzon.newsfeedbackend.external_call` | Logging en kostenberekening van externe API-aanroepen (`external_calls`-tabel) |
| `podcast_source` | `com.vdzon.newsfeedbackend.podcast_source` | Ingest van podcast-RSS-bronnen en episode-verwerking (transcript-lookup) |
| `version` | `com.vdzon.newsfeedbackend.version` | Build-/versie-info endpoint |
| `common` | `com.vdzon.newsfeedbackend.common` | Gedeelde helpers (security, exceptions, Jackson-config) |

### Moduleregels (Spring Modulith)
- Klassen in subpackages van een module zijn **privé** voor die module; alleen klassen direct in de moduleroot (of expliciet gemarkeerd als `@ApplicationModule(type = OPEN)`) zijn van buiten toegankelijk.
- Modules mogen **geen directe imports** doen van interne klassen van andere modules.
- Communicatie tussen modules verloopt via:
  - Directe aanroep van de **publieke service-interface** van de doelmodule
  - Spring Application Events (`ApplicationEventPublisher`) voor losgekoppelde communicatie
- Spring Modulith-moduleregels kunnen worden afgedwongen met een test die `ApplicationModules.of(Application::class.java).verify()` aanroept; zo'n verificatie-test is op dit moment **niet** in de repo aanwezig (zie §7).

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
| `newsfeed.events.discovered` | Counter | `username` | Events ontdekt |
| `newsfeed.events.discovery.duration` | Timer | `username` | Duur event-discovery |
| `newsfeed.event_videos.discovered` | Counter | `username` | Event-video's ontdekt |
| `newsfeed.event_videos.discovery.duration` | Timer | `username` | Duur video-discovery |
| `newsfeed.event_videos.summary.count` | Counter | `result` | Video-samenvattingen |
| `newsfeed.event_videos.summary.duration` | Timer | `result` | Duur video-samenvatting |

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
De automatische tests draaien met `mvn test` (JUnit 5 / Kotlin). De huidige suite
in `src/test/kotlin/com/vdzon/newsfeedbackend/` bestaat uit gerichte unit-tests:

- `rss/RssFetcherImageUrlTest.kt` — extractie van de afbeeldings-URL uit RSS
- `ai/AiPricingPropertiesTest.kt` — OpenAI-prijsconfiguratie (`app.ai.pricing`)
- `podcast/domain/PodcastScriptParserTest.kt` — parser van INTERVIEWER/GAST-scripts

### Beschikbare testtooling
De `pom.xml` bevat naast JUnit 5 ook test-dependencies voor Cucumber
(`cucumber-spring`, `cucumber-junit-platform-engine`) en WireMock
(`wiremock-standalone`). Die zijn bedoeld voor toekomstige Spring Boot
integratietests met gestubde externe API's (OpenAI, Tavily, TTS); er zijn op dit
moment **nog geen** feature-bestanden, step-definitions of WireMock-stubs in de
repo. Nieuwe integratietests volgen bij voorkeur de Given/When/Then-conventie in
het Nederlands.

> Spring Modulith-moduleregels kunnen geverifieerd worden met een test die
> `ApplicationModules.of(Application::class.java).verify()` aanroept; voeg zo'n
> test toe wanneer modulegrenzen geborgd moeten worden.

---

## 8. API-contract (`openapi.yaml`)

`specs/openapi.yaml` is het **handmatig onderhouden** contract voor de REST-API en geldt als source of truth voor de interface tussen backend en frontend. Er is **geen** code-generatiestap in de build: `pom.xml` bevat geen `openapi-generator-maven-plugin` en er bestaat geen `target/generated-sources/openapi/`-map.

De controllers zijn met de hand geschreven Spring `@RestController`-klassen (`@RequestMapping` + `@GetMapping`/`@PostMapping`/…) die direct met de bestaande Kotlin-domein-/DTO-modellen werken.

**Werkwijze bij een API-wijziging:** pas zowel `openapi.yaml` als de betreffende controller aan en houd ze consistent (paden, methoden, request/response-vorm). `openapi.yaml` wordt zo de bron die de implementatie beschrijft, niet genereert.
