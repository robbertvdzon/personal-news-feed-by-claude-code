# SF-573 — Gedrag-neutrale code-kwaliteit: SOLID-refactors + build-warnings oplossen

Story (parent SF-572): nightly: Code-kwaliteit verbeteren (SOLID, build-output).
Doel: gedrag-neutraal de codekwaliteit verbeteren (SOLID, naamgeving, dode code,
duplicatie, functielengte) en build-warnings/deprecations wegwerken, zonder enige
functionele wijziging. Een kleine of lege diff is een geldige uitkomst.

## Stappenplan

- [x] Issue + factory-docs lezen (`development.md`, `technical-spec.md`, `specs/backend-technical-spec.md`, agent-tips)
- [x] Backend-conventies verifiëren (`@param:`-target, logger, modulith-lagen, DTO-plaatsing, KDoc)
- [x] Backend-build op warnings controleren (`mvn clean compile`)
- [x] Backend-tests draaien (`mvn test`)
- [x] Backend scannen op veilige SOLID-/dode-code-/duplicatie-refactors
- [x] Frontends + Android Gradle-builds beoordelen op haalbaarheid van warning-fixes
- [x] Story-log + worklog bijwerken met resultaat en concrete meldingen

## Gedaan en waarom

### Backend (`newsfeedbackend/newsfeedbackend/`, Kotlin) — geen code-diff nodig

De backend is op de veilig-herstelbare conventies al volledig conform; er was geen
gedrag-neutrale verbetering meer aan te brengen. Geverifieerd:

- **`@Value` use-site-target:** geen kale `@Value` meer op een `val`/`var`-property.
  De enige twee resterende treffers zijn de bewust níet aan te passen uitzonderingen
  (`@param:` zou daar fout/nieuwe warnings geven): `PodcastAsyncConfig.kt` (`@Value`
  op een `@Bean`-methodeparameter) en `PodcastTranscriptWorker.kt` (`@Value` op een
  plain constructor-param zonder `val`).
- **Logger:** alle treffers van `LoggerFactory.getLogger` volgen het patroon
  `private val log = LoggerFactory.getLogger(javaClass)`; geen afwijkingen.
- **Comments:** de aanwezige comments zijn WHY-comments of sectie-scheiders, conform
  "geen comments tenzij de WHY niet-vanzelfsprekend is".
- **Dode code:** de `@Suppress("UNUSED_PARAMETER")` op de `map(rs, rowNum)`-functies
  is geen dode code maar de verplichte `RowMapper`-signatuur van JdbcTemplate; laten staan.
- **Lagen / DTO-plaatsing / KDoc-positie:** ongewijzigd conform eerdere convergentie
  (SF-437, SF-502/SF-504).

Build-output backend:

- `mvn clean compile` → **BUILD SUCCESS, 0 compiler-warnings, 0 deprecations**.
- `mvn test` → **Tests run: 25, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
  (de huidige suite zijn 4 pure unit-tests; geen `PNF_DATABASE_URL` nodig).

De integratietests en de e2e-suite zijn niet aangeraakt.

### Frontends (`frontend/`, `frontend-reader/`) — niet uitgevoerd, met reden

Het Dart-/Flutter-spoor en de twee Android Gradle-builds zijn in deze run **niet**
aangepast, omdat de wijzigingen niet aantoonbaar veilig (warning weg, géén nieuwe
warning) konden worden gemaakt in deze runner:

- Geen `flutter`/`dart`-binary aanwezig → `flutter analyze`/`flutter test` kan niet
  lokaal draaien.
- Geen `gradle`/`gradlew`-toolchain, geen Android SDK en geen netwerk-cache; bovendien
  ontbreekt `android/local.properties` (vereist `flutter.sdk`), waardoor
  `settings.gradle.kts` de Gradle-build meteen zou afbreken. De Android Gradle-build
  is dus niet uitvoerbaar en warning-output niet meetbaar.

Concreet geïdentificeerde, maar bewust niet-geforceerde Android Gradle-deprecatie
(identiek in `frontend/android/app/build.gradle.kts` en
`frontend-reader/android/app/build.gradle.kts`): het `kotlinOptions { jvmTarget = ... }`
-blok is met Kotlin Gradle Plugin 2.x deprecated ten gunste van een `compilerOptions`
-DSL. Dit is een plausibele, mechanische fix, maar de exacte DSL-vorm is plugin-/
AGP-versie-afhankelijk en een foute migratie breekt de Android release-build —
niet detecteerbaar zonder draaiende Gradle-build. Conform AC5 ("niet veilig
door te voeren → niet doen, concreet melden") is deze fix overgelaten aan een
omgeving waar de Android-build daadwerkelijk groen te verifiëren is.

Dit is dezelfde toolchain-beperking en conclusie als in de vorige gelijksoortige
nightly (SF-343), waar het frontend-/Android-spoor om exact dezelfde reden buiten
de run is gehouden.

### Bekende, niet binnen deze story recht te trekken afwijkingen (gemeld, niet geforceerd)

- Jackson gebruikt overal `com.fasterxml.jackson` (pom.xml + imports); de spec is
  hierop inmiddels uitgelijnd, dus dit is geen afwijking meer — migreren zou een
  risicovolle dependency-change zijn.
- Cross-module imports van interne `domain`/`infrastructure`-klassen en
  domeinmodellen die direct als HTTP-response worden geserialiseerd zijn
  architecturale refactors (geen mechanische, gedrag-neutrale fix) en blijven open.
- `SettingsController.kt` heeft bewust geen klasse-`@RequestMapping` omdat het
  meerdere prefixes bedient (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`);
  een base-path toevoegen zou URLs wijzigen.

## Resultaat

Gedrag-neutraal; geen API-/contractwijziging (`specs/openapi.yaml` ongemoeid), geen
Flyway-/schema-wijziging, geen wijziging aan integratie- of e2e-tests. Backend is
geverifieerd warning-vrij en groen. Code-diff is leeg op de doc-/worklog-bestanden na
— een geldige uitkomst voor deze story.
