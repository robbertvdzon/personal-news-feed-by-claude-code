# Technical Spec

## Stack

### Backend
- **Taal**: Kotlin 2.2 (JVM 21)
- **Framework**: Spring Boot 4.0.6 + Spring Modulith
- **Build**: Maven, root: `newsfeedbackend/newsfeedbackend/`
- **Database**: PostgreSQL (Neon cloud), migraties via Flyway (`src/main/resources/db/migration/V*.sql`)
- **Auth**: JWT (eigen implementatie, `app.jwt.*`)
- **AI**: Anthropic Claude API (`AnthropicClient`), Tavily search API
- **Scheduled pipelines**: `@Async` + Spring `@EventListener`, per-user `ReentrantLock` om dubbele runs te voorkomen

### Frontend
- **Framework**: Flutter 3 (Dart SDK ^3.9.0)
- **State**: Riverpod 2
- **Platforms**: web + Android (APK)
- **API-communicatie**: `frontend/lib/api/`

## Architectuur

De backend is opgebouwd als Spring Modulith: losse modules per domein
(`events`, `rss`, `feed`, `settings`, `auth`, `request`, …). Modules
communiceren via Spring Application Events, niet via directe imports.

## Database migraties

Flyway-migraties in `newsfeedbackend/newsfeedbackend/src/main/resources/db/migration/`.
Naamconventie: `V<n>__<omschrijving>.sql`. Migratie V14 voegt `event_preferences`
en `event_denylist` toe (KAN-68).

## Codeconventies

- Geen comments behalve voor niet-voor-de-hand-liggende why's.
- Kotlin `data class` voor domeinmodellen en request/response-bodies.
- Controllers doen niets anders dan security-check + service-delegatie.
- Pipelinesklassen hebben hun eigen Spring-event-type (bijv.
  `EventDiscoveryRequested`) voor async-triggers.
- Frontend-schermen gebruiken `ConsumerStatefulWidget` + Riverpod providers.

## Bekende valkuilen

- `AnthropicClient.complete()` is synchroon; roep hem nooit vanuit de
  request-thread aan bij endpoints waar latency telt — gebruik een aparte
  `@Async`-pipeline.
- Neon heeft een connection-pool limiet; `hikari.maximum-pool-size=5` is opzettelijk laag.
- De Flutter-web-build verwacht `API_BASE_URL` als `--dart-define` argument (zie `frontend/Makefile`).
