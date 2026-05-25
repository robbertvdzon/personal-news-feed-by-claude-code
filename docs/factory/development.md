# Development

## Backend

Projectroot: `newsfeedbackend/newsfeedbackend/` (open dit als IntelliJ project-root, niet de bovenliggende map).

### Commands

- Build: `cd newsfeedbackend/newsfeedbackend && mvn clean package -DskipTests`
- Unit tests: `cd newsfeedbackend/newsfeedbackend && mvn test`
- Integration tests: `cd newsfeedbackend/newsfeedbackend && mvn verify` (vereist `PNF_DATABASE_URL` env-var)
- Start lokaal: `cd newsfeedbackend/newsfeedbackend && mvn spring-boot:run`

### Gegenereerde bronnen

OpenAPI Generator genereert Kotlin-interfaces in `target/generated-sources/openapi/src/main/kotlin/`. IntelliJ markeert dit automatisch als bronnenmap via de Maven-plugin.

## Frontend

Projectroot: `frontend/`

### Commands

- Web-app starten (dev): `cd frontend && make serve-ext` (poort 3000, backend op localhost:8080)
- Web-app extern bereikbaar: `cd frontend && make serve-public`
- Tests: `cd frontend && flutter test`
- APK bouwen (prod): `cd frontend && make build-apk-ext`

## Conventions

### Repostructuur

```
newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/
  <module>/            ← één package per Spring Modulith module
    <Module>Service.kt ← publieke interface (cross-module aanroepbaar)
    api/               ← @RestController + DTOs
    internal/          ← module-privé implementatie
frontend/lib/
  screens/             ← Flutter schermen
  providers/           ← Riverpod providers
  api/                 ← HTTP-client calls
  models/              ← dataklassen
```

### Naamgeving

- Backend: Kotlin, camelCase klassen, PascalCase types, snake_case DB-kolommen.
- Frontend: Dart, camelCase variabelen, PascalCase klassen/widgets.
- Branches: `ai/<ISSUE-KEY>` (factory), `feat/`, `fix/`, `chore/`, `docs/` voor handmatig werk.
- Commit format op `ai/`-branches: `<ISSUE-KEY>: <omschrijving>` (verplicht, CI valideert dit).

### Teststrategie

- Backend unit-tests: JUnit5, mock externe dependencies.
- Spring Modulith verificatie: één test `ApplicationModules.of(...).verify()` — faalt als moduleregels worden overtreden.
- Integration tests: Cucumber-scenario's (zie `specs/backend-technical-spec.md` en `e2e/`).
- Frontend: `flutter test` in `frontend/`.

### API-contract

`specs/openapi.yaml` is de source of truth. De OpenAPI Generator Maven Plugin genereert de Kotlin-interfaces; controllers implementeren deze gegenereerde interfaces. Wijzig nooit de gegenereerde code direct — pas `specs/openapi.yaml` aan en draai `mvn generate-sources`.
