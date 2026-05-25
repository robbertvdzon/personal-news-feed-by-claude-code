# Technical Spec

## Tech Stack

| Onderdeel | Technologie | Versie |
|-----------|------------|--------|
| Backend taal | Kotlin | 2.x |
| Backend framework | Spring Boot | 4.x |
| Build tool | Maven | - |
| Module-architectuur | Spring Modulith | - |
| Database | PostgreSQL (Neon hosted) | - |
| Migraties | Flyway | - |
| JSON-serialisatie | Jackson 3.x (`tools.jackson`, **niet** `com.fasterxml.jackson`) | 3.x |
| Security | Spring Security + JWT (`jjwt`) | - |
| RSS parsing | ROME (`com.rometools:rome`) | - |
| WebSocket | Spring WebSocket (niet WebFlux) | - |
| Metrics | Micrometer + Prometheus | - |
| Frontend | Flutter + Dart | Dart ^3.9 |
| State management | Riverpod | - |
| Audio | just_audio | - |

## Spring Modulith modules

| Module | Verantwoordelijkheid |
|--------|----------------------|
| `auth` | JWT, registratie, login, gebruikersbeheer |
| `rss` | RSS-feeds ophalen, AI-verwerking, feed-selectie |
| `feed` | Gecureerde feed-items, feedback, cleanup |
| `request` | Ad-hoc verzoeken en dagelijkse updates |
| `podcast` | Podcast generatie (script + audio) |
| `settings` | Categorie-instellingen en RSS-feed URLs per gebruiker |
| `events` | Event management: discovery, denylist, video-discovery |
| `ai` | Gedeelde Anthropic Claude client |
| `storage` | JSON-bestandsopslag utilities |
| `websocket` | WebSocket handler voor request-statusupdates |

## Architectuurregels

- Modules mogen alleen via de **publieke service-interface** (klassen direct in de moduleroot) van andere modules afhangen.
- Module-interne klassen staan in subpackages en zijn niet toegankelijk van buiten de module.
- Cross-module communicatie: directe aanroep van publieke interface of `ApplicationEventPublisher`.
- De Spring Modulith verificatie-test (`ApplicationModules.of(...).verify()`) blokkeert bij moduleregels-overtreding.

## Events module — verwijderen van events

Events kunnen verwijderd worden via `DELETE /api/events/{id}`. De flow:

1. `EventController` roept `EventService.delete(id)` aan.
2. `EventServiceImpl.delete()` verwijdert het gekoppelde `FeedItem` en het event zelf (hard delete).
3. Event-id wordt toegevoegd aan de `event_denylist`-tabel (per user, genormaliseerde slug).
4. `EventDiscoveryPipeline` controleert de denylist vóór opslaan — overgeslagen events komen niet terug.
5. `EventVideoDiscoveryPipeline` laadt events via `events.load()` — hard-deleted events zijn er niet meer in.

## Database

- Verbinding via `PNF_DATABASE_URL` (env-var, ook als `SPRING_DATASOURCE_URL`).
- Flyway beheert alle schema-migraties; migraties staan in `src/main/resources/db/migration/`.
- **Opgelet:** alle open PR-previews praten met dezelfde Postgres als productie. Migraties in een PR worden direct op prod-data toegepast.

## Codeconventies

- Kotlin: idiomatic Kotlin (data classes, extension functions, null-safety).
- Geen directe code in `target/generated-sources/` — alleen `specs/openapi.yaml` aanpassen.
- Jackson 3.x groupId: `tools.jackson` (Spring Boot 4 default), **niet** `com.fasterxml.jackson`.
- JVM-tijdzone: `Europe/Amsterdam` (gezet via `TZ` env-var in deployment).
- Heap: `XX:MaxRAMPercentage=70.0` (container-limit is 1Gi).

## Bekende valkuilen

- IntelliJ openen op `newsfeedbackend/newsfeedbackend/` (Maven-root), niet op `newsfeedbackend/`.
- Jackson groupId is `tools.jackson` in Spring Boot 4 — gebruik nooit de oude `com.fasterxml.jackson` groep in nieuwe code.
- `spring-boot-starter-websocket` gebruiken, **niet** WebFlux.
- Alle API-controllers moeten de gegenereerde interface implementeren (niet direct `@RequestMapping` zetten).
