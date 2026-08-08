# Technical Spec — Personal News Feed

## Stack

| Laag | Technologie |
|------|------------|
| Backend taal | Kotlin 2.x |
| Backend framework | Spring Boot 4.x, poort 8080 |
| Build tool backend | Maven — root: `newsfeedbackend/newsfeedbackend/` |
| Modulestructuur | Spring Modulith (`ModuleStructureTest`, lege allowlist, draait bij elke `mvn test`) |
| Database | PostgreSQL (Neon cloud), Flyway-migraties |
| Frontend | Flutter / Dart ^3.9 — iOS, Android, web |
| State management | Riverpod 2.x |
| AI | OpenAI (samenvatting, selectie, discovery, transcriptie/TTS) |
| Search | Tavily websearch |
| TTS | OpenAI TTS / ElevenLabs |
| Deployment | OpenShift + ArgoCD (GitOps) |

## Maven-projectroot

Open `newsfeedbackend/newsfeedbackend/` als projectroot in IntelliJ (níet de bovenliggende `newsfeedbackend/`). De `pom.xml` bevindt zich daar.

## Modulestructuur (Spring Modulith)

Elke module is een top-level package onder `com.vdzon.newsfeedbackend`. Modules mogen alleen via de publieke service-interface of Spring Application Events communiceren — nooit via interne klassen van andere modules.

| Module | Package-root | Verantwoordelijkheid |
|--------|-------------|----------------------|
| `auth` | `…auth` | Registratie, login, JWT |
| `rss` | `…rss` | RSS-feeds ophalen en samenvatten |
| `feed` | `…feed` | Gecureerde feed-items |
| `request` | `…request` | Ad-hoc verzoeken en dagelijkse updates |
| `podcast` | `…podcast` | Podcastgeneratie (script + audio) |
| `settings` | `…settings` | Gebruikersinstellingen (categorieën, feed-lijsten) |
| `ai` | `…ai` | Gedeelde OpenAI-client + prijsconfiguratie |

> Belangrijkste modules; de volledige lijst (o.a. `admin`, `external_call`, `podcast_source`, `storage`, `websocket`, `version`, `common`) staat in `specs/backend-technical-spec.md` §3.

## Lagenstructuur per module

```
module/
├── ModuleService.kt          ← publieke interface (zichtbaar voor andere modules)
├── api/
│   ├── ModuleController.kt   ← @RestController
│   └── dto/                  ← request/response DTOs
├── domain/
│   ├── ModuleServiceImpl.kt  ← business logic (privé)
│   └── ModuleModel.kt        ← domeinmodel (privé)
└── infrastructure/
    └── ModuleRepository.kt   ← DB/opslag (privé)
```

## API-contract

`specs/openapi.yaml` is het handmatig onderhouden contract en de **source of truth** voor de API. Er is geen code-generatiestap: de controllers zijn met de hand geschreven Spring `@RestController`-klassen (`@RequestMapping`/`@GetMapping`/…) die consistent met `openapi.yaml` worden gehouden. `pom.xml` bevat geen OpenAPI Generator-plugin.

## Database

- PostgreSQL via Neon; lokaal verbinding via `PNF_DATABASE_URL`.
- Flyway-migraties: `newsfeedbackend/newsfeedbackend/src/main/resources/db/migration/V*.sql`.
- Podcast-audio staat als BYTEA in de `podcasts`-tabel (niet op filesystem).

## Codeconventies

- Geen comments tenzij de WHY niet-vanzelfsprekend is.
- Logging: SLF4J — INFO voor job-start/einde, DEBUG voor externe API-aanroepen, WARN voor herstelbare fouten, ERROR voor niet-herstelbare fouten.
- Authenticatie: JWT Bearer (HS256, 30 dagen geldig); alle endpoints beveiligd behalve de publieke: `/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**` en `/actuator/**`. Admin-only: `/api/admin/**` (`ROLE_ADMIN`).
- Jackson: `pom.xml` en vrijwel alle main-code-imports gebruiken de `tools.jackson`-groupId (Jackson 3, kern-mapper + `jackson-module-kotlin`; java-time is ingebouwd, geen aparte `jackson-datatype-jsr310`-module meer). Uitzondering: `jackson-annotations` (`@JsonProperty` e.d.) blijft bewust onder `com.fasterxml.jackson.annotation` (niet hernoemd in Jackson 3). Los daarvan gebruikt `jjwt-jackson` intern nog Jackson 2 voor JWT-payloads — een bewuste, geïsoleerde afwijking.
- `@Value` op een primary-constructor-*property* (`val`) krijgt een expliciet `@param:`-use-site-target (`@param:Value(...)`); dit voorkomt de Kotlin 2.x-waarschuwing over het toekomstige default-annotation-target en houdt injectie via de constructor-parameter identiek. Uitzonderingen: `@Value` op een `@Bean`-methodeparameter en op een plain constructor-parameter zonder `val`/`var` blijven zonder target (daar is `@param:` ongeldig resp. redundant).
- Subprocessen draaien via `ProcessBuilder` met een expliciete argumentenlijst (nooit een shell-string), zodat klassieke command-injectie niet mogelijk is. De huidige aanroepers (`media/AudioTranscoder.kt`, `podcast/infrastructure/Mp3Concatenator.kt`, beide ffmpeg) geven uitsluitend interne temp-paden door. Wordt er ooit wél een extern/gebruiker-beïnvloedbare waarde als positioneel argument doorgegeven, zet er dan de `--` end-of-options-separator vlak vóór, zodat een waarde die met `-` begint niet als vlag wordt geïnterpreteerd (argument-injectie-hardening). Voor geldige invoer is het gedrag identiek.
- Android Gradle (Flutter-frontends, KGP 2.1.0): stel het Kotlin JVM-target in via het top-level `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }`-blok (met `import org.jetbrains.kotlin.gradle.dsl.JvmTarget`), niet via het binnen `android {}` deprecated `kotlinOptions { jvmTarget = ... }`-blok. Het JVM-target blijft 11 (identiek aan `compileOptions { source/targetCompatibility = VERSION_11 }`); dit is puur een DSL-modernisering die de KGP-deprecation-warning uit de build-output haalt zonder gedrag te wijzigen.
- Jackson `JsonNode`: gebruik `asString()` / `asString(String)`, niet de gedeprecate `asText()` / `asText(String)` (Jackson 3, `tools.jackson`). `asText()` is een `final` wrapper die naar `asString()` delegeert — identieke signatuur en semantiek, pure mechanische rename zonder gedragswijziging. Dit geldt ook voor testcode: sinds SF-1945 staan er nul `asText(`-aanroepen meer in `newsfeedbackend/newsfeedbackend/src/test/` (92 stuks vervangen in de e2e-tests), waardoor `mvn clean test` warning-vrij is en een nieuwe `[WARNING]`-regel meteen opvalt.
- Foutbodies van de backend hebben altijd de vorm `{"error": "…"}` — `GlobalExceptionHandler` in `common/Exceptions.kt` serialiseert élke fout zo (dus géén Spring-standaardbody met `message`/`timestamp`/`path`). Frontend-code die een servermelding aan de gebruiker toont, leest daarom het `error`-veld en valt terug op de rauwe body; zie de gedeelde helper `extractDutchMessage` in `frontend/lib/api/api_client.dart` (gebruikt door `rss_feeds_screen.dart` en `categories_screen.dart`).
- Schrijfacties in Riverpod-notifiers die een servervalidatie kunnen falen (bijv. `RssFeedsNotifier.save` / `PodcastFeedsNotifier.save` / `SettingsNotifier.save`) muteren `state` pas **nadat** de PUT en het cache-schrijven geslaagd zijn, en laten de exception naar de UI propageren. Optimistische updates zijn alleen geschikt voor acties die de server niet kan weigeren; anders blijft er een fantoom-item in de lijst staan tot de volgende refresh.
- Neon draait op scale-to-zero (suspend na 300s stilte, max 1 CU — SF-1739), en de app mag die suspend niet blokkeren: achtergrondwerk start event-driven (`ApplicationEventPublisher` + `@EventListener @Async`), niet via een poll. Voeg dus geen `@Scheduled` toe die vaker dan één keer per uur de database raakt — gebruik een uurlijkse recovery-/vangnetjob met `@SchedulerLock` (patroon: `PodcastRecoveryScheduler`). Idem: geen HikariCP `keepaliveTime` of validatie-query op een timer, `spring.datasource.hikari.minimum-idle=0` blijft staan (zonder die regel is `minimumIdle == maximumPoolSize` en doet `idle-timeout` niets), en de K8s-probes gebruiken de standaard Spring health-groepen — voeg geen `management.endpoint.health.group.*`-config toe die de db-indicator in een probe trekt. Zie runbook §6.1.
- SSRF-hardening voor URLs die server-side gefetcht worden — zowel user-ingevoerde URLs (bijv. RSS-feed-URLs) als tweede-orde-URLs die uit opgehaalde externe inhoud komen (bijv. de artikel-URL uit een feed-item, SF-1843): valideer via `common.SsrfUrlValidator` — alleen `http`/`https`, en de host mag niet resolven naar een loopback-, link-local-, private- (RFC1918/ULA) of multicast-adres. Valideer zowel bij opslaan (als er een opslagmoment is) als vlak vóór de daadwerkelijke fetch (verse DNS-resolutie, dekt DNS-rebinding af); tweede-orde-URLs hebben alleen dat tweede moment. Zie `specs/backend-functional-spec.md` §7.5 voor het volledige gedrag.
- Externe API-aanroepen worden gelogd via de gedeelde default-methode `ExternalCallLogger.logCall(...)` (SF-2022), niet door zelf een `ExternalCall(...)` te bouwen: buiten het package `external_call` staat in `src/main` nul `ExternalCall(`-constructie. De helper vult `id` (random UUID), `endTime` en `durationMs` in en vangt zelf elke exception af met één `log.warn("[ExternalCallLog] …")` — loggen mag de business-flow nooit stukmaken, en dat vangnet zit dus in de interface in plaats van in elke client. Wat per aanroeper verschilt (provider, action, units, costUsd, status, errorMessage, `subject` inclusief eventuele `.take(120)`-afkapping) blijft bij de aanroeper; de helper kapt bewust niets af. Omdat het een *default*-implementatie op de interface is, blijven test-fakes die een anonieme `object : ExternalCallLogger` bouwen met alleen `log(call)` gewoon werken.
- Endpoints die op `permitAll` staan (in de praktijk `/api/shared/**`) geven **nooit** een domeinmodel terug, maar een eigen response-DTO in `module/api/dto/` die per constructie alleen de velden bevat die de publieke consument nodig heeft: `SharedFeedItemDto` zonder `isRead`/`starred`/`liked` (SF-1884) en `SharedCategoryDto` zonder `extraInstructions`/`isSystem` (SF-1992). Een gedeeld domeinmodel groeit later stilzwijgend met privévelden mee en lekt die dan publiek; de KDoc van zo'n shared-DTO benoemt daarom expliciet welke velden ontbreken en waarom. Sinds SF-1992 hebben beide `/api/shared/*`-endpoints zo'n DTO.
