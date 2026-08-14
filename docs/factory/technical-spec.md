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

Zonder generatiestap én zonder contract-test faalt er niets als schema en Kotlin-model uit elkaar lopen; vergelijk een gewijzigd schema dus met de hand veld-vóór-veld met de data class, in beide richtingen (SF-2073 vond zo drie fantoom-`costUsd`-velden, één ontbrekend veld en één te streng type). Twee huisregels die daaruit volgen: een berekende property in de class-body (`val isTranslation: Boolean get() = …`) wordt wél geserialiseerd en hoort dus in het schema, met een `description` "berekende property, alleen in responses" en **niet** met `readOnly: true` (nul voorkomens in `openapi.yaml`); en een `$ref` naar een enum-schema mag alleen als het veld in Kotlin ook dat enum-type is — een veld dat als `String` met `enum.name` gevuld wordt, is `type: string` met een `description` over de herkomst. Zie `specs/backend-technical-spec.md` §8 voor de uitwerking.

Diezelfde stille drift geldt voor de **foutcodes**, niet alleen voor de schema's: een operatie zonder `'404'` faalt nergens, ook al gooit de service er een `NotFoundException`. Loop bij een wijziging daarom beide richtingen na (SF-2094 vond zo zes ontbrekende `'404'`-responses en één `'404'` die in de code een 409 was):

- **Code → contract:** elke `NotFoundException`-throw-site die vanaf een endpoint bereikbaar is, heeft een `'404'` op de bijbehorende operatie in `openapi.yaml`.
- **Contract → code:** elke `'404'` in `openapi.yaml` heeft een 404-bron in de code — een `NotFoundException` óf een expliciete `ResponseEntity.notFound()` (zoals `RssController` bij `GET /api/rss/{id}/transcript`). Wijkt de code af van het contract, kies dan de kant van het contract zolang die bij het patroon van de andere endpoints past: SF-2094 maakte van de `ConflictException` in `PodcastTranslationServiceImpl.startTranslation` een `NotFoundException`, omdat 409 daar gereserveerd is voor state-conflicten (status ≠ `DONE`, leeg transcript) en "aflevering onbestaand" op alle andere endpoints 404 is.

Een 404-`description` legt ook de *reden* vast, niet alleen de status: bij een eigenaarscheck (zie de codeconventie hieronder) hoort de formulering "onbekend id, of de resource is van een andere gebruiker", en bij een beleidsweigering (een vast verzoek dat niet verwijderd mág worden) hoort dat er expliciet bij. Anders staat de keuze alleen in een code-comment en leest een client-bouwer een 404 als "bestaat niet". `openapi.yaml` kent geen gedeelde `components/responses`; een `'404'` is er een kaal blok met alleen een `description`, en dat patroon blijft zo.

De derde driftlaag zit in de delen die niemand parseert: `example:`-waarden, ontbrekende `enum`'s en opsommingen in prozateksten (SF-2130 vond ze alle drie tegelijk in het rolmodel). Drie huisregels:

- **Een `example:` is een waarde, geen constantnaam.** `AdminUserView.role` en `SetRoleRequest.role` hadden `example: ROLE_USER`/`ROLE_ADMIN` — de námen van de Kotlin-constanten in `auth/domain/User.kt`, terwijl de waarden `"user"` en `"admin"` zijn. Een client die het voorbeeld overnam kreeg een `400`. Neem een `example:` dus letterlijk over uit de code (of uit een e2e-assertie), nooit uit een constant-identifier.
- **Een gesloten waardenverzameling krijgt een inline `enum`.** Dwingt de code de waarden expliciet af (bijv. `setRole` weigert alles buiten `user`/`admin` met een `BadRequestException`), dan hoort dat in het schema als `enum: [user, admin]`. Dat spreekt de `$ref`-regel hierboven niet tegen: die verbiedt een `$ref` naar een enum-schéma voor een veld dat in Kotlin geen enum is; een inline `enum` op zo'n `String`-veld beschrijft juist wél precies wat er gebeurt.
- **Prozateksten met een opsomming zijn een tweede kopie van de code.** De `info.description` noemde drie publieke paden waar `SecurityConfig` er vijf op `permitAll` zet (`/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**`, `/actuator/**`). Wijzigt zo'n lijst in de code, dan hoort de `description` in dezelfde diff. Let bij zulke correcties op de omgekeerde valkuil: `ROLE_ADMIN` in een tag, `summary` of `403`-`description` is de Spring-autoriteitsnaam uit `hasRole("ADMIN")` en daar correct — een brede zoek-en-vervang op `ROLE_` maakt het contract juist stuk.

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
- Jackson `JsonNode`: gebruik `asString()` / `asString(String)`, niet de gedeprecate `asText()` / `asText(String)` (Jackson 3, `tools.jackson`). `asText()` is een `final` wrapper die naar `asString()` delegeert — identieke signatuur en semantiek, pure mechanische rename zonder gedragswijziging. Dit geldt ook voor testcode: sinds SF-1945 staan er nul `asText(`-aanroepen meer in `newsfeedbackend/newsfeedbackend/src/test/` (92 stuks vervangen in de e2e-tests), waardoor `mvn clean test` warning-vrij is en een nieuwe `[WARNING]`-regel meteen opvalt. (De JVM-kant van diezelfde ruis — Mockito's dynamic self-attach en de CDS-regel — is sinds SF-2151 weg via de surefire-/failsafe-`<argLine>`; zie `development.md` § Tests draaien.)
- Foutbodies van de backend hebben altijd de vorm `{"error": "…"}` — `GlobalExceptionHandler` in `common/Exceptions.kt` serialiseert élke fout zo (dus géén Spring-standaardbody met `message`/`timestamp`/`path`). Frontend-code die een servermelding aan de gebruiker toont, leest daarom het `error`-veld en valt terug op de rauwe body; zie de gedeelde helper `extractDutchMessage` in `frontend/lib/api/api_client.dart` (gebruikt door `rss_feeds_screen.dart` en `categories_screen.dart`).
- Schrijfacties in Riverpod-notifiers die een servervalidatie kunnen falen (bijv. `RssFeedsNotifier.save` / `PodcastFeedsNotifier.save` / `SettingsNotifier.save`) muteren `state` pas **nadat** de PUT en het cache-schrijven geslaagd zijn, en laten de exception naar de UI propageren. Optimistische updates zijn alleen geschikt voor acties die de server niet kan weigeren; anders blijft er een fantoom-item in de lijst staan tot de volgende refresh.
- Een UI-conditie en de refresh-/poll-timer die bij diezelfde conditie hoort lezen **één gedeelde constante**, nooit twee losse lijsten. Concreet: de "podcast is nog bezig"-statussen staan uitsluitend in de top-level `const kPodcastInProgressStatuses` (`Set<String>`, `k`-prefix conform Dart-conventie) in `frontend/lib/models/models.dart`; `podcast_screen.dart` gebruikt hem zowel voor de spinner/het statuslabel als voor `_maybePoll`, en `podcast_detail_screen.dart` voor zijn eigen poll-timer (SF-2066). Lopen zulke lijsten uiteen, dan is de fout stil en vervelend: de spinner draait door terwijl er niet meer ververst wordt (dat gebeurde bij `TRANSLATING`/`TTS_GENERATING`). Leg de inhoud van zo'n gedeelde set vast in een test (`frontend/test/podcast_in_progress_statuses_test.dart`). Een bewust *smallere* lijst voor een deelflow krijgt óók één naam en blijft apart: `const kPodcastTranslationInProgressStatuses` (`PENDING`/`TRANSLATING`/`TTS_GENERATING`) is de vertaalflow van één RSS-aflevering (`EpisodeLookup.translationInProgress`, op `translatedPodcastStatus` — niet de status van de podcast zelf) en is per constructie een deelverzameling van de gedeelde set, want een vertaling doorloopt nooit de generatie-statussen (SF-2123). Dat is geen kopie — documenteer de relatie in de comment bij beide constantes en leg de deelverzameling vast in dezelfde test, zodat het uiteenlopen van de twee lijsten meteen rood wordt.
- Neon draait op scale-to-zero (suspend na 300s stilte, max 1 CU — SF-1739), en de app mag die suspend niet blokkeren: achtergrondwerk start event-driven (`ApplicationEventPublisher` + `@EventListener @Async`), niet via een poll. Voeg dus geen `@Scheduled` toe die vaker dan één keer per uur de database raakt — gebruik een uurlijkse recovery-/vangnetjob met `@SchedulerLock` (patroon: `PodcastRecoveryScheduler`). Idem: geen HikariCP `keepaliveTime` of validatie-query op een timer, `spring.datasource.hikari.minimum-idle=0` blijft staan (zonder die regel is `minimumIdle == maximumPoolSize` en doet `idle-timeout` niets), en de K8s-probes gebruiken de standaard Spring health-groepen — voeg geen `management.endpoint.health.group.*`-config toe die de db-indicator in een probe trekt. Zie runbook §6.1.
- SSRF-hardening voor URLs die server-side gefetcht worden — zowel user-ingevoerde URLs (bijv. RSS-feed-URLs) als tweede-orde-URLs die uit opgehaalde externe inhoud komen (bijv. de artikel-URL uit een feed-item, SF-1843): valideer via `common.SsrfUrlValidator` — alleen `http`/`https`, en de host mag niet resolven naar een loopback-, link-local-, private- (RFC1918/ULA) of multicast-adres. Valideer zowel bij opslaan (als er een opslagmoment is) als vlak vóór de daadwerkelijke fetch (verse DNS-resolutie, dekt DNS-rebinding af); tweede-orde-URLs hebben alleen dat tweede moment. Zie `specs/backend-functional-spec.md` §7.5 voor het volledige gedrag.
- Externe API-aanroepen worden gelogd via de gedeelde default-methode `ExternalCallLogger.logCall(...)` (SF-2022), niet door zelf een `ExternalCall(...)` te bouwen: buiten het package `external_call` staat in `src/main` nul `ExternalCall(`-constructie. De helper vult `id` (random UUID), `endTime` en `durationMs` in en vangt zelf elke exception af met één `log.warn("[ExternalCallLog] …")` — loggen mag de business-flow nooit stukmaken, en dat vangnet zit dus in de interface in plaats van in elke client. Wat per aanroeper verschilt (provider, action, units, costUsd, status, errorMessage, `subject` inclusief eventuele `.take(120)`-afkapping) blijft bij de aanroeper; de helper kapt bewust niets af. Omdat het een *default*-implementatie op de interface is, blijven test-fakes die een anonieme `object : ExternalCallLogger` bouwen met alleen `log(call)` gewoon werken.
- Endpoints die op een resource-id werken doen de **eigenaarscheck vóór elke mutatie**, niet erna: haal de resource eerst op via de per-gebruiker gefilterde lookup (bijv. `get(username, id)`, die `repo.load(username)` filtert) en muteer pas als die iets oplevert. Levert de lookup niets op (onbekend id óf andermans resource), dan volgt `404` via `NotFoundException("<resource> $id")` — bewust géén `403`, zodat het antwoord niet verraadt of een id van een andere gebruiker bestaat; `delete`, `rerun` en (sinds SF-2051) `cancel` in `RequestController` volgen alle drie dat patroon, net als `delete` in `PodcastController`. Die 404 hoort óók in `openapi.yaml` te staan met de reden erbij ("onbekend id, of de resource is van een andere gebruiker") — stond hij daar niet, dan is de privacykeuze onzichtbaar voor iedereen die alleen het contract leest (SF-2094 vulde dat aan voor `deleteRequest`, `rerunRequest` en `deletePodcast`). In-memory state die aan een resource hangt (zoals de `cancellation`-map in `RequestServiceImpl`) wordt per gebruiker gekeyed (`"$username/$id"` via een private `cancelKey`) zodat de vlag van gebruiker A de verwerking van B nooit kan raken, en er komt alleen een sleutel in ná een geslaagde eigenaarscheck — anders kan iedereen met zelfverzonnen id's het geheugen ongelimiteerd laten groeien. Let op: het id zelf is geen geheim (`/ws/requests` staat op `permitAll` en broadcast alle statusupdates), dus de check is de enige grens.
- Endpoints die op `permitAll` staan (in de praktijk `/api/shared/**`) geven **nooit** een domeinmodel terug, maar een eigen response-DTO in `module/api/dto/` die per constructie alleen de velden bevat die de publieke consument nodig heeft: `SharedFeedItemDto` zonder `isRead`/`starred`/`liked` (SF-1884) en `SharedCategoryDto` zonder `extraInstructions`/`isSystem` (SF-1992). Een gedeeld domeinmodel groeit later stilzwijgend met privévelden mee en lekt die dan publiek; de KDoc van zo'n shared-DTO benoemt daarom expliciet welke velden ontbreken en waarom. Sinds SF-1992 hebben beide `/api/shared/*`-endpoints zo'n DTO. Eén bekende uitzondering: de WebSocket `/ws/requests` staat óók op `permitAll` en broadcast het volledige `NewsRequest`-domeinmodel naar álle verbonden clients (bewust, de frontend filtert zelf — zie `specs/backend-functional-spec.md` §5). Er zit daar geen DTO-laag tussen, dus een veld dat aan `NewsRequest` wordt toegevoegd gaat automatisch mee de lijn over; sinds SF-2109 pint `RequestWebSocketE2eTest` die serialisatie vast, maar de afweging "mag dit veld publiek?" blijft handwerk bij elke uitbreiding van het model.
