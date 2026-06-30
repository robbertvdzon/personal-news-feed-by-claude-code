# Technical Spec — Personal News Feed

## Stack

| Laag | Technologie |
|------|------------|
| Backend taal | Kotlin 2.x |
| Backend framework | Spring Boot 4.x, poort 8080 |
| Build tool backend | Maven — root: `newsfeedbackend/newsfeedbackend/` |
| Modulestructuur | Spring Modulith (verify-test optioneel; nog niet in de repo) |
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
| `settings` | `…settings` | Gebruikersinstellingen, denylist, event-voorkeuren |
| `events` | `…events` | Tech-events ontdekken en beheren |
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

## Events & denylist (PNF-2)

- `event_denylist`-tabel (V14-migratie): per-user soft-delete van events.
- `EventDiscoveryPipeline` laadt de denylist bij elke run en slaat events op de denylist over (geen Tavily/OpenAI-calls).
- `DELETE /api/events/{id}`: verwijdert het event + bijbehorende FeedItem, voegt event-ID toe aan `event_denylist`.
- Frontend: prullenbak-icoon in `EventDetailScreen` — na verwijderen navigeert de app terug naar de lijst.

## Codeconventies

- Geen comments tenzij de WHY niet-vanzelfsprekend is.
- Logging: SLF4J — INFO voor job-start/einde, DEBUG voor externe API-aanroepen, WARN voor herstelbare fouten, ERROR voor niet-herstelbare fouten.
- Authenticatie: JWT Bearer (HS256, 30 dagen geldig); alle endpoints beveiligd behalve de publieke: `/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**` en `/actuator/**`. Admin-only: `/api/admin/**` (`ROLE_ADMIN`).
- Jackson: `pom.xml` en alle imports gebruiken de `com.fasterxml.jackson`-groupId (`jackson-module-kotlin`, `jackson-datatype-jsr310`).
- `@Value` op een primary-constructor-*property* (`val`) krijgt een expliciet `@param:`-use-site-target (`@param:Value(...)`); dit voorkomt de Kotlin 2.x-waarschuwing over het toekomstige default-annotation-target en houdt injectie via de constructor-parameter identiek. Uitzonderingen: `@Value` op een `@Bean`-methodeparameter en op een plain constructor-parameter zonder `val`/`var` blijven zonder target (daar is `@param:` ongeldig resp. redundant).
- Subprocessen draaien via `ProcessBuilder` met een expliciete argumentenlijst (nooit een shell-string), zodat klassieke command-injectie niet mogelijk is. Wordt een extern/gebruiker-beïnvloedbare waarde als positioneel argument doorgegeven (bijv. de video-URL aan `yt-dlp` in `VideoAudioDownloader`), zet er dan de `--` end-of-options-separator vlak vóór, zodat een waarde die met `-` begint niet als vlag wordt geïnterpreteerd (argument-injectie-hardening). Voor geldige invoer is het gedrag identiek.
- Android Gradle (Flutter-frontends, KGP 2.1.0): stel het Kotlin JVM-target in via het top-level `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }`-blok (met `import org.jetbrains.kotlin.gradle.dsl.JvmTarget`), niet via het binnen `android {}` deprecated `kotlinOptions { jvmTarget = ... }`-blok. Het JVM-target blijft 11 (identiek aan `compileOptions { source/targetCompatibility = VERSION_11 }`); dit is puur een DSL-modernisering die de KGP-deprecation-warning uit de build-output haalt zonder gedrag te wijzigen.
