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

# Unit-tests + ModuleStructureTest (snel, geen Docker; sluit **/e2e/** uit)
mvn test

# E2e-suite erbij (Testcontainers/Docker vereist, elke e2e-klasse in eigen JVM-fork)
mvn verify

# Eén specifieke test draaien, bijv. de Spring Modulith-verificatietest:
# mvn test -Dtest=ModuleStructureTest
```

> `mvn test` (surefire) draait de unit-tests plus `ModuleStructureTest`
> (Spring Modulith-verificatie, lege allowlist). `mvn verify` (failsafe)
> draait daarnaast de e2e-suite onder `src/test/kotlin/.../e2e/` (Testcontainers-Postgres,
> echte Flyway-migraties, alleen externe diensten gefaked) — zie
> `specs/backend-technical-spec.md` §7. De Cucumber/WireMock-dependencies staan
> nog in `pom.xml`, maar lijken ongebruikt (geen feature-bestanden/stubs
> gevonden); de e2e-strategie is in de praktijk vervangen door Testcontainers.

### Frontend (Flutter)

Er zijn twee Flutter-apps: `frontend/` (de hoofd-app) en `frontend-reader/`
(de read-only reader-app).

```bash
cd frontend

# Web (lokaal, geforward op poort 3000)
make serve-ext

# Android APK
make build-apk-ext

# Tests van de hoofd-app
flutter pub get
flutter test

# Tests van de reader-app
cd ../frontend-reader
flutter pub get
flutter test
```

> CI dwingt beide testsuites af via `.github/workflows/frontend-tests.yml`
> (twee losse jobs, één per app) bij elke pull request en elke push naar
> `main`. Zie ook [Tests draaien](#tests-draaien).

## Lokale backend starten

Stel de vereiste environment-variabelen in (zie `secrets-local.md`) en start via:

```bash
cd newsfeedbackend/newsfeedbackend
mvn spring-boot:run
```

Of via IntelliJ (open `newsfeedbackend/newsfeedbackend/` als project).

## Conventions

- **Maven-root**: open altijd `newsfeedbackend/newsfeedbackend/` als IntelliJ-projectroot.
- **OpenAPI-first**: wijzig eerst `specs/openapi.yaml`, daarna de met de hand geschreven Kotlin-`@RestController`-implementatie, en houd beide consistent. Er is geen code-generatiestap (geen OpenAPI Generator-plugin in `pom.xml`).
- **Spring Modulith**: modules communiceren alleen via publieke service-interfaces of Spring Application Events. `ModuleStructureTest` (`ApplicationModules…verify()`, lege allowlist) dwingt dit af en draait bij elke `mvn test` (zie `specs/backend-technical-spec.md` §7); raadpleeg/pas die test aan bij nieuwe modulegrens-schendingen in plaats van een nieuwe test toe te voegen.
- **Flyway**: nieuwe database-wijzigingen toevoegen als `V{n+1}__beschrijving.sql` in `src/main/resources/db/migration/`.
- **Branches**: prefix `ai/` voor factory-branches (bv. `ai/PNF-2`).
- **Commits**: Nederlandstalige of Engelstalige boodschappen; geen force-push naar main.

## Tests draaien

### Backend

```bash
# Snel: unit-tests + ModuleStructureTest, geen Docker nodig
cd newsfeedbackend/newsfeedbackend
mvn test

# Incl. e2e-suite (Testcontainers/Docker vereist), zoals ook geconfigureerd
# in .factory/verification.yaml (command backend-maven-verify)
mvn verify
```

`mvn test` (surefire) draait alleen de snelle unit-tests + `ModuleStructureTest`
en sluit `**/e2e/**` uit. `mvn verify` (failsafe) draait daarnaast de
e2e-suite onder `src/test/kotlin/.../e2e/` (o.a.
`RssRefreshE2eTest`/`SettingsE2eTest`/`PodcastIngestE2eTest`, harnas
`E2eTestBase`/`E2eTestConfig`, fakes `FakeOpenAiChatClient`/`FakeContentServer`) —
zie `specs/backend-technical-spec.md` §7. De Cucumber- en WireMock-dependencies
staan nog in `pom.xml`, maar lijken ongebruikt (geen feature-bestanden,
step-definitions of WireMock-stubs gevonden).

De testbuild is sinds SF-1945 warning-vrij: `mvn -B clean test` produceert nul
`[WARNING]`-regels (daarvóór 92 `asText()`-deprecations uit de e2e-tests).
Er is geen build-gate die op warnings faalt, dus behandel elke nieuwe
`[WARNING]` in de output als een signaal dat je zelf oppakt.

### Frontend (Flutter)

Beide Flutter-apps hebben een eigen testsuite; draai ze vanuit de map van
de betreffende app:

```bash
# Hoofd-app
cd frontend
flutter pub get
flutter test

# Reader-app (relatief t.o.v. frontend/ hierboven)
cd ../frontend-reader
flutter pub get
flutter test
```

Net zoals `mvn verify` het vangnet voor de backend is, dwingt
`.github/workflows/frontend-tests.yml` deze twee suites af in CI — bij elke
pull request en elke push naar `main` die `frontend/**` of
`frontend-reader/**` raakt. De workflow gebruikt twee losse jobs, zodat de
uitslag van beide apps per run zichtbaar blijft, ook als er één faalt. Er is
geen Docker/JDK nodig: `flutter test` draait op de Dart-VM.

> Let op: `flutter pub get`/`flutter test` in `frontend-reader/` kan
> `frontend-reader/pubspec.lock` muteren zonder dat `pubspec.yaml` wijzigt.
> Zet zo'n kale lockfile-drift terug (`git checkout -- frontend-reader/pubspec.lock`)
> voordat je afrondt.
