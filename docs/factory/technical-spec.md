# Technical Spec — Personal News Feed

## Stack

| Laag | Technologie |
|------|------------|
| Backend taal | Kotlin 2.x |
| Backend framework | Spring Boot 4.x, poort 8080 |
| Build tool backend | Maven — root: `newsfeedbackend/newsfeedbackend/` |
| Modulestructuur | Spring Modulith (afdwingen via verify-test) |
| Database | PostgreSQL (Neon cloud), Flyway-migraties |
| Frontend | Flutter / Dart ^3.9 — iOS, Android, web |
| State management | Riverpod 2.x |
| AI | Anthropic Claude (samenvatting, selectie, discovery) |
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
| `ai` | `…ai` | Gedeelde Anthropic Claude-client |

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

`specs/openapi.yaml` is de **source of truth**. De OpenAPI Generator Maven Plugin genereert Kotlin-interfaces in `target/generated-sources/openapi/`; controllers implementeren deze interfaces.

## Database

- PostgreSQL via Neon; lokaal verbinding via `PNF_DATABASE_URL`.
- Flyway-migraties: `newsfeedbackend/newsfeedbackend/src/main/resources/db/migration/V*.sql`.
- Podcast-audio staat als BYTEA in de `podcasts`-tabel (niet op filesystem).

## Events & denylist (PNF-2)

- `event_denylist`-tabel (V14-migratie): per-user soft-delete van events.
- `EventDiscoveryPipeline` laadt de denylist bij elke run en slaat events op de denylist over (geen Tavily/Claude-calls).
- `DELETE /api/events/{id}`: verwijdert het event + bijbehorende FeedItem, voegt event-ID toe aan `event_denylist`.
- Frontend: prullenbak-icoon in `EventDetailScreen` — na verwijderen navigeert de app terug naar de lijst.

## Codeconventies

- Geen comments tenzij de WHY niet-vanzelfsprekend is.
- Logging: SLF4J — INFO voor job-start/einde, DEBUG voor externe API-aanroepen, WARN voor herstelbare fouten, ERROR voor niet-herstelbare fouten.
- Authenticatie: JWT Bearer (HS256, 30 dagen geldig); alle endpoints beveiligd behalve `/api/auth/**`, `/api/version`, `/ws/**`.
- Jackson 3.x groupId: `tools.jackson` (niet `com.fasterxml.jackson` — Spring Boot 4 verschil).
