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
Sinds SF-2151 is óók de JVM-ruis weg: de uitvoer bevat nul regels die matchen op
`grep -icE 'warning|deprecat|self-attach'` (baseline 6 regels — Mockito's
dynamic-self-attach-waarschuwing van de JDK plus de bijbehorende
`Sharing is only supported for boot loader classes`-CDS-regels). Er is geen
build-gate die op warnings faalt, dus behandel elke nieuwe `[WARNING]`- of
JVM-waarschuwingsregel in de output als een signaal dat je zelf oppakt.
Controleer met de log in een bestand:

```bash
cd newsfeedbackend/newsfeedbackend
mvn -B --no-transfer-progress clean test | tee /tmp/mvntest.log
grep -icE 'warning|deprecat|self-attach' /tmp/mvntest.log   # hoort 0 te zijn
```

Grep nooit op `WARN`: de SSRF-unittests loggen legitieme logback-`WARN`-regels
die niet op `warning` matchen.

Die nul komt uit drie samenhangende stukjes `<build><plugins>`-config in
`pom.xml` — raak ze alleen samen aan:

- `maven-surefire-plugin` en `maven-failsafe-plugin` hebben allebei
  `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>`.
  De `-javaagent` geeft Mockito expliciet als Java-agent mee (JDK 21
  waarschuwt over dynamic self-attach, een latere JDK maakt er een fout van);
  `-Xshare:off` haalt de CDS-waarschuwing weg die het toevoegen van een
  javaagent oproept.
- De `@{argLine}`-prefix is **verplicht**: `jacoco:prepare-agent` (resp.
  `prepare-agent-integration`) zet zelf de property `argLine`. Een
  hardgecodeerde `<argLine>` zonder die prefix overschrijft de JaCoCo-agent
  stil — build groen, alle tests groen, geen melding, maar
  `target/jacoco.exec` / `target/jacoco-it.exec` wordt niet meer geschreven.
  Controleer na een wijziging aan een `<argLine>` dus altijd dat die bestanden
  bestaan (~335 KB resp. ~8,5 MB).
- `maven-dependency-plugin` draait het goal `properties` (default-phase
  `initialize`, dus vóór `jacoco:prepare-agent`). Zonder die stap blijft de
  placeholder `${org.mockito:mockito-core:jar}` ongevuld — surefire vult hem
  alleen zelf in voor *directe* dependencies, en `mockito-core` komt hier
  transitief via `spring-boot-starter-test` binnen. De fork crasht dan met
  `Error opening zip file or JAR manifest missing` /
  `The forked VM terminated without properly saying goodbye`.

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
