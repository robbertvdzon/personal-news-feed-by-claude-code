# Personal News Feed — Backend Technische Specificatie

> **Doel van dit document:** Architectuur, codestructuur, patterns, monitoring en testing van de backend. Voor functioneel gedrag: zie [`backend-functional-spec.md`](./backend-functional-spec.md).

---

## 1. Tech Stack & Dependencies

**Taal/platform:** Spring Boot **4.x**, Kotlin **2.x**, poort 8080.

**Build tool:** Maven (`pom.xml`).

**Belangrijke dependencies:**

| Dependency | Doel |
|-----------|------|
| `tools.jackson` (Jackson 3.x) | JSON-serialisatie — let op: **niet** `com.fasterxml.jackson`, Spring Boot 4 gebruikt een andere groupId |
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

### Gegenereerde bronnen
De OpenAPI Generator Maven Plugin genereert Kotlin-interfaces in `target/generated-sources/openapi/src/main/kotlin/`. IntelliJ markeert deze map automatisch als bronnenmap via de Maven-plugin. De gegenereerde interfaces staan in package `com.vdzon.newsfeedbackend.api`.

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
| `settings` | `com.vdzon.newsfeedbackend.settings` | Categorie-instellingen en RSS-feed URLs per gebruiker |
| `ai` | `com.vdzon.newsfeedbackend.ai` | Gedeelde Anthropic Claude client (gebruikt door rss, feed, request, podcast) |
| `storage` | `com.vdzon.newsfeedbackend.storage` | Gedeelde JSON-bestandsopslag utilities |
| `websocket` | `com.vdzon.newsfeedbackend.websocket` | WebSocket handler voor request-statusupdates |

### Moduleregels (Spring Modulith)
- Klassen in subpackages van een module zijn **privé** voor die module; alleen klassen direct in de moduleroot (of expliciet gemarkeerd als `@ApplicationModule(type = OPEN)`) zijn van buiten toegankelijk.
- Modules mogen **geen directe imports** doen van interne klassen van andere modules.
- Communicatie tussen modules verloopt via:
  - Directe aanroep van de **publieke service-interface** van de doelmodule
  - Spring Application Events (`ApplicationEventPublisher`) voor losgekoppelde communicatie
- Spring Modulith verificatie-test: één test die `ApplicationModules.of(Application::class.java).verify()` aanroept en faalt als moduleregels worden overtreden.

### Packagestructuur per module (voorbeeld: `rss`)
```
com.vdzon.newsfeedbackend.rss/
├── RssService.kt               ← publieke interface (zichtbaar voor andere modules)
├── RssFeedService.kt           ← publieke interface
├── api/
│   ├── RssController.kt        ← @RestController, implementeert gegenereerde RSSItemsApi
│   ├── RssFeedsController.kt   ← @RestController, implementeert gegenereerde RSSFeedsApi
│   └── dto/
│       ├── RssItemResponse.kt  ← response DTO (wat de API teruggeeft)
│       └── FeedbackRequest.kt  ← request DTO
├── domain/
│   ├── RssServiceImpl.kt       ← implementatie (privé)
│   ├── RssFeedServiceImpl.kt   ← implementatie (privé)
│   ├── RssItem.kt              ← domeinmodel (privé)
│   └── RssFeedPipeline.kt      ← pipeline orchestratie (privé)
└── infrastructure/
    ├── RssItemRepository.kt    ← JSON-opslag (privé)
    ├── RssFeedRepository.kt    ← JSON-opslag (privé)
    └── RssFetcher.kt           ← HTTP RSS-fetch (privé)
```

Dezelfde structuur geldt voor alle andere modules.

---

## 4. Gelaagde Architectuur

Elke module volgt een strikte drielagenstructuur: **API → Domain → Infrastructure**.

### Laag 1: API (Controller)
- Implementeert de gegenereerde interface uit `openapi.yaml` (bijv. `RSSItemsApi`)
- Ontvangt HTTP-requests met **request DTOs**
- Roept de publieke service-interface aan (nooit rechtstreeks de repository)
- Mapt domeinmodellen naar **response DTOs** vóór teruggave
- Bevat **geen** business logic
- Leest de ingelogde gebruiker via `SecurityContextHolder.getContext().authentication!!.name`

### Laag 2: Domain (Service)
- Implementeert de publieke service-interface
- Bevat alle **business logic**
- Werkt intern met **domeinmodellen** (geen DTOs, geen persistentiemodellen)
- Mag andere modules aanroepen via hun publieke service-interface
- Mag `@Async` gebruiken voor achtergrondverwerking
- Mag events publiceren via `ApplicationEventPublisher`

### Laag 3: Infrastructure (Repository / Adapter)
- **Repository:** leest en schrijft JSON-bestanden; geeft domeinmodellen terug
- **Externe adapters:** HTTP-clients voor Anthropic, Tavily, TTS-providers; geven domeinmodellen of primitieven terug
- Bevat geen business logic
- Is volledig privé binnen de module

### DTO-regels

| Context | Type | Locatie |
|---------|------|---------|
| HTTP-request body inkomend | Request DTO (`*Request`, `*Dto`) | `module/api/dto/` |
| HTTP-response body uitgaand | Response DTO (`*Response`) | `module/api/dto/` |
| Interne verwerking tussen lagen | Domeinmodel | `module/domain/` |
| JSON-bestandsopslag | Persistentiemodel (mag gelijk zijn aan domeinmodel) | `module/infrastructure/` |

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
[RSS]   feed-item {i}/{m}: {title-truncated-80}    (per item, voor lange Claude-calls)
[RSS] klaar: {n} nieuwe artikelen, {m} in feed, duur {s}s
[Summary] dagelijkse samenvatting aangemaakt voor '{username}'
```

Deze stap-voor-stap progress-logs zijn essentieel voor de gebruiker tijdens een lange run — met API-keys duurt stap 2 al gauw enkele minuten bij ~50 items, en stap 4 een paar seconden per geselecteerd item. Zonder deze logs is er geen feedback tussen de start- en klaar-regels in.

**Externe API-aanroepen (DEBUG):**
```
[Anthropic] Aanroep '{operationNaam}' voor gebruiker '{username}' — {n} tokens
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
- **Development** (`application-dev.properties`): leesbare tekstopmaak
- **Productie**: JSON-formaat via `logstash-logback-encoder` (compatibel met Grafana Loki)

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

| Metriek | Type | Labels | Beschrijving |
|---------|------|--------|--------------|
| `newsfeed.rss.fetch.duration` | Timer | `username` | Duur RSS-verwerkingspipeline |
| `newsfeed.rss.items.processed` | Counter | `username` | Artikelen verwerkt |
| `newsfeed.rss.items.in.feed` | Counter | `username` | Artikelen geselecteerd voor feed |
| `newsfeed.ai.calls.total` | Counter | `operation`, `model` | Totaal Claude API-aanroepen |
| `newsfeed.ai.calls.duration` | Timer | `operation`, `model` | Latency Claude API |
| `newsfeed.ai.cost.usd` | DistributionSummary | `operation` | Geschatte kosten per aanroep |
| `newsfeed.ai.retries` | Counter | `operation` | Retry-pogingen bij rate limiting |
| `newsfeed.podcast.generated` | Counter | `ttsProvider`, `status` | Podcasts gegenereerd |
| `newsfeed.podcast.duration` | Timer | — | Generatieduur podcast |
| `newsfeed.requests.processed` | Counter | `type`, `status` | Verzoeken afgerond |

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

## 7. Integratie Tests met Cucumber

### Doel
Elke feature in de functionele spec wordt gedekt door minimaal één Cucumber-scenario. De tests draaien als Spring Boot integratietest met een echte, maar lege datamap.

### Dependencies
```xml
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-spring</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-junit-platform-engine</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <scope>test</scope>
</dependency>
```

### Structuur
```
src/test/
├── kotlin/com/vdzon/newsfeedbackend/
│   ├── CucumberTestRunner.kt          ← @Suite, @SelectClasspathResource("features")
│   ├── CucumberSpringConfig.kt        ← @SpringBootTest, @CucumberContextConfiguration
│   └── steps/
│       ├── AuthSteps.kt
│       ├── RssSteps.kt
│       ├── FeedSteps.kt
│       ├── RequestSteps.kt
│       ├── PodcastSteps.kt
│       └── SettingsSteps.kt
└── resources/
    ├── features/
    │   ├── auth.feature
    │   ├── rss.feature
    │   ├── feed.feature
    │   ├── requests.feature
    │   ├── podcast.feature
    │   ├── settings.feature
    │   ├── daily_update.feature
    │   └── daily_summary.feature
    └── wiremock/
        ├── anthropic/                 ← stub-responses voor Claude API
        ├── tavily/                    ← stub-responses voor Tavily
        └── tts/                       ← stub-responses voor OpenAI/ElevenLabs TTS
```

### Test setup
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` — echte HTTP-requests via `TestRestTemplate`
- WireMock-server start voor alle tests; stubt Anthropic, Tavily en TTS endpoints
- Tijdelijke datamap per testrun (via `@TempDir` of `app.data-dir` in test-properties)
- Elke scenario begint met een schone staat (gebruikersdata wordt voor elk scenario opgeruimd)

### Feature-bestand conventies
Schrijf scenario's in het **Nederlands**, in Given/When/Then-stijl:

```gherkin
# features/rss.feature
Functionaliteit: RSS-artikelen verwerken

  Scenario: Nieuwe RSS-artikelen worden opgehaald en samengevat
    Gegeven een ingelogde gebruiker met RSS-feed "https://example.com/rss"
    En de feed bevat 3 nieuwe artikelen
    En de AI-stub geeft geldige samenvattingen terug
    Als de dagelijkse update wordt getriggerd
    Dan bevat de RSS-itemlijst 3 nieuwe items
    En elk item heeft een samenvatting in het Nederlands
    En elk item heeft een categorie toegewezen

  Scenario: Artikel wordt geselecteerd voor de feed
    Gegeven een ingelogde gebruiker met 5 verwerkte RSS-artikelen
    En de AI-stub selecteert artikelen 1 en 3 voor de feed
    Als de feed-selectie wordt uitgevoerd
    Dan hebben artikelen 1 en 3 inFeed=true
    En zijn er 2 FeedItems aangemaakt
```

### Spring Modulith verificatietest
```kotlin
// src/test/kotlin/.../ModuleStructureTest.kt
@Test
fun `modulestructuur voldoet aan Modulith regels`() {
    ApplicationModules.of(Application::class.java).verify()
}
```

---

## 8. Spec-first API (OpenAPI Generator)

De REST-interfaces worden gegenereerd uit `specs/openapi.yaml`. Controllers implementeren de gegenereerde interfaces; de generator maakt **geen** modellen aan (de bestaande Kotlin-domeinmodellen worden hergebruikt).

### Maven plugin configuratie
```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.12.0</version>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
            <configuration>
                <inputSpec>${project.basedir}/../../specs/openapi.yaml</inputSpec>
                <generatorName>kotlin-spring</generatorName>
                <apiPackage>com.vdzon.newsfeedbackend.api</apiPackage>
                <modelPackage>com.vdzon.newsfeedbackend.model</modelPackage>
                <generateModels>false</generateModels>
                <configOptions>
                    <interfaceOnly>true</interfaceOnly>
                    <useSpringBoot3>true</useSpringBoot3>
                    <useTags>true</useTags>
                    <documentationProvider>none</documentationProvider>
                    <skipDefaultInterface>true</skipDefaultInterface>
                    <useBeanValidation>false</useBeanValidation>
                </configOptions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

De `kotlin-maven-plugin` moet de gegenereerde bronnenmap meenemen:
```xml
<sourceDir>${project.build.directory}/generated-sources/openapi/src/main/kotlin</sourceDir>
```
