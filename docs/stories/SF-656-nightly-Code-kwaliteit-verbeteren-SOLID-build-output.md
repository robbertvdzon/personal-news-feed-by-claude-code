# SF-656 / SF-657 — Gedrag-neutrale code-kwaliteit & build-warnings

Story (parent SF-656, subtaak SF-657): nightly — Code-kwaliteit verbeteren (SOLID, build-output).
Doel: gedrag-neutraal de codekwaliteit verbeteren (SOLID, naamgeving, dode code,
duplicatie, functielengte) en build-warnings/deprecations wegwerken over de backend
(Maven, `newsfeedbackend/newsfeedbackend/`) en de twee Android Gradle-frontends
(`frontend/android/`, `frontend-reader/android/`). Geen functionele wijziging.
Een kleine of lege diff is een vooraf benoemde, geldige uitkomst.

## Stappenplan

- [x] Issue + factory-docs lezen (`development.md`, `technical-spec.md`, `specs/backend-technical-spec.md`, agent-tips)
- [x] Backend-conventies verifiëren (`@param:Value`-target, logger, modulith-lagen, DTO-plaatsing)
- [x] Backend-build op warnings controleren (`mvn clean compile`)
- [x] Backend-tests draaien (`mvn test`)
- [x] Backend scannen op veilige SOLID-/dode-code-/duplicatie-refactors
- [x] Android Gradle-builds beoordelen op haalbaarheid van warning-fixes
- [x] Story-log + worklog bijwerken met resultaat en concrete meldingen

## Gedaan en waarom

### Backend (`newsfeedbackend/newsfeedbackend/`, Kotlin) — geen code-diff nodig

De backend is op de veilig-herstelbare conventies volledig conform; er was geen
gedrag-neutrale verbetering meer aan te brengen. Geverifieerd via grep + build:

- **`@Value` use-site-target:** geen kale `@Value` op een `val`/`var`-property. De
  enige twee treffers zijn de bewust níet aan te passen uitzonderingen (`@param:` zou
  daar een compilefout of nieuwe warning geven): `PodcastAsyncConfig.kt` (`@Value` op
  een `@Bean`-methodeparameter) en `PodcastTranscriptWorker.kt` (`@Value` op een plain
  constructor-param zonder `val`).
- **Logger:** alle treffers van `LoggerFactory.getLogger` volgen exact
  `private val log = LoggerFactory.getLogger(javaClass)`; geen afwijkingen.
- **Inline DTO's:** geen `data class` in `*Controller.kt` (request-DTO's staan in
  `module/api/dto/`, geconvergeerd in SF-437).
- **Lagen / KDoc-positie:** ongewijzigd conform eerdere convergentie (SF-502/SF-504,
  SF-586/SF-588).

Build-output backend:

- `mvn clean compile` → **BUILD SUCCESS, 0 compiler-warnings, 0 deprecations**.
- `mvn test` → **Tests run: 28, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
  (huidige suite: RssFetcherImageUrlTest 6, AiPricingPropertiesTest 4,
  ApiRequestDtoContractTest 6, PodcastScriptParserTest 9, VideoAudioDownloaderArgsTest 3;
  geen `PNF_DATABASE_URL` nodig voor deze unit-tests).

De integratietests en de e2e-suite zijn niet aangeraakt.

### Android Gradle-frontends — niet uitgevoerd, met reden (AC: niet-veilig → melden)

De twee Android Gradle-builds (`frontend/android/`, `frontend-reader/android/`) zijn in
deze run **niet** aangepast, omdat een warning-fix niet aantoonbaar veilig (warning weg,
géén nieuwe warning, build groen) te maken is in deze runner:

- Geen `flutter`/`dart`-binary, geen `gradle`-binary en geen `gradlew`-wrapper aanwezig.
- Geen `android/local.properties`; `settings.gradle.kts` doet `require(flutterSdkPath != null)`
  en breekt de configuratiefase dus meteen af zonder `flutter.sdk`.
- Geen Android SDK / netwerk-cache → de Gradle-build is niet uitvoerbaar en warning-output
  niet meetbaar.

Concreet geïdentificeerde, maar bewust niet-geforceerde deprecatie (identiek in
`frontend/android/app/build.gradle.kts` en `frontend-reader/android/app/build.gradle.kts`):
het `kotlinOptions { jvmTarget = ... }`-blok is met de Kotlin Gradle Plugin 2.x (hier
`org.jetbrains.kotlin.android` 2.1.0) deprecated ten gunste van de `compilerOptions`-DSL.
De migratie is mechanisch maar plugin-/AGP-versie-afhankelijk (juiste vorm + `JvmTarget`-import,
plaatsing van het `kotlin { compilerOptions { } }`-blok), en een onjuiste migratie breekt de
release-APK-build — niet detecteerbaar zonder draaiende Gradle-build. Conform de acceptance
criteria ("bij twijfel of niet veilig door te voeren → niet doen, concreet melden") is deze fix
overgelaten aan een omgeving met een werkende Android-toolchain (CI / devcontainer). Zelfde
toolchain-beperking en conclusie als de eerdere nightly's SF-343 en SF-572/573.

### Bekende, niet binnen deze story recht te trekken afwijkingen (gemeld, niet geforceerd)

- Jackson gebruikt overal `com.fasterxml.jackson` (pom.xml + imports); de spec is hierop
  inmiddels uitgelijnd (sinds SF-502) — geen afwijking meer, migreren zou een risicovolle
  dependency-change zijn.
- Cross-module imports van interne `domain`/`infrastructure`-klassen en domeinmodellen die
  direct als HTTP-response worden geserialiseerd zijn architecturale refactors (geen
  mechanische, gedrag-neutrale fix) en blijven open.
- `SettingsController.kt` heeft bewust geen klasse-`@RequestMapping` omdat het meerdere
  prefixes bedient (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`); een base-path
  toevoegen zou URLs wijzigen.

## Resultaat

Gedrag-neutraal; geen API-/contractwijziging (`specs/openapi.yaml` ongemoeid), geen
Flyway-/schema-wijziging, geen wijziging aan integratie- of e2e-tests. Backend is geverifieerd
warning-vrij en groen (28 tests). Code-diff is leeg op de story-log/worklog na — een geldige,
onderbouwd gemelde uitkomst voor deze nightly housekeeping-story.
