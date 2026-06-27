# Development — Personal News Feed

## Omgeving

Een gecontaineriseerde dev-omgeving is beschikbaar via `.devcontainer/`. Bevat JDK 21, Maven, Flutter 3.35, Android SDK, Node 20 en Claude Code.

```bash
devcontainer up --workspace-folder .
devcontainer exec --workspace-folder . zsh
```

## Commands

### Backend (Maven-root: `newsfeedbackend/newsfeedbackend/`)

```bash
# Build (skip tests)
cd newsfeedbackend/newsfeedbackend
mvn -DskipTests package

# Unit-tests (huidige suite: enkele JUnit/Kotlin-tests onder src/test/kotlin)
mvn test

# Eén specifieke test draaien (indien aanwezig), bijv. een Spring Modulith-verificatietest:
# mvn test -Dtest=ModuleStructureTest
```

> Let op: de repo bevat momenteel alleen een beperkt aantal unit-tests; de
> Cucumber/WireMock-integratietests en een `ModuleStructureTest` zijn nog niet
> aanwezig (de dependencies staan wel in `pom.xml`). Zie
> `specs/backend-technical-spec.md` §7.

### Frontend (Flutter)

```bash
cd frontend

# Web (lokaal, geforward op poort 3000)
make serve-ext

# Android APK
make build-apk-ext
```

## Lokale backend starten

Stel de vereiste environment-variabelen in (zie `secrets-local.md`) en start via:

```bash
cd newsfeedbackend/newsfeedbackend
mvn spring-boot:run
```

Of via IntelliJ (open `newsfeedbackend/newsfeedbackend/` als project).

## Conventions

- **Maven-root**: open altijd `newsfeedbackend/newsfeedbackend/` als IntelliJ-projectroot.
- **OpenAPI-first**: wijzig eerst `specs/openapi.yaml`, daarna de Kotlin-implementatie. Gegenereerde interfaces staan in `target/generated-sources/openapi/`.
- **Spring Modulith**: modules communiceren alleen via publieke service-interfaces of Spring Application Events. Voer na module-wijzigingen `ModuleStructureTest` uit.
- **Flyway**: nieuwe database-wijzigingen toevoegen als `V{n+1}__beschrijving.sql` in `src/main/resources/db/migration/`.
- **Branches**: prefix `ai/` voor factory-branches (bv. `ai/PNF-2`).
- **Commits**: Nederlandstalige of Engelstalige boodschappen; geen force-push naar main.

## Tests draaien

```bash
# Alle tests (vereist lokale database-verbinding via PNF_DATABASE_URL)
cd newsfeedbackend/newsfeedbackend
mvn test
```

Cucumber-integratietests (in `src/test/resources/features/`) draaien als Spring Boot-integratietest met WireMock voor externe API-stubs.
