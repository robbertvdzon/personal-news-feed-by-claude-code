# SF-572 - Worklog

Story-context bij eerste pickup:
Gedrag-neutrale code-kwaliteit: SOLID-refactors + build-warnings oplossen

Voer gedrag-neutraal kwaliteits-/refactorwerk uit. Backend (newsfeedbackend/newsfeedbackend/, Kotlin): kleine veilige SOLID-refactors (naamgeving, dode code, duplicatie, te lange functies/klassen) conform docs/factory/technical-spec.md (geen comments tenzij WHY, @param:-target bij @Value, lagen API->domain->infrastructure) en specs/backend-technical-spec.md (Spring Modulith-modulegrenzen). Frontends (frontend/, frontend-reader/): zelfde soort opschoning waar aantoonbaar veilig. Build-output: warnings/deprecations oplossen in de Maven-backend (mvn -DskipTests package, mvn test) en in beide Android Gradle-builds (frontend/android/, frontend-reader/android/). Hard: functioneel gedrag exact gelijk, geen API-/gedragswijzigingen (specs/openapi.yaml), integratietests en e2e-suite NIET aanpassen; moet je dat wel om groen te krijgen of twijfel je of een wijziging gedrag verandert -> doe de wijziging niet en ga in error met concrete melding. Houd mvn test groen (vereist PNF_DATABASE_URL). Bekende niet-veilige afwijkingen (Jackson com.fasterxml vs tools.jackson, cross-module imports, domeinmodel-als-HTTP-response) en niet-veilig-te-fixen warnings melden in worklog/PR met reden i.p.v. forceren. Voer een eigen review-stap uit. Een kleine of lege diff is een geldige uitkomst.

## SF-573 (developer) — 2026-06-28

Stappenplan:
[x]: read issue and target docs
[x]: backend-conventies + build-warnings verifiëren
[x]: backend-tests draaien
[x]: frontends/Android beoordelen op veilig haalbare warning-fixes
[x]: story-log + worklog bijwerken met resultaat

Done / rationale:
- **Backend al volledig conform; geen code-diff nodig.** Geverifieerd via grep:
  geen kale `@Value` op val/var (alleen de 2 gedocumenteerde uitzonderingen
  PodcastAsyncConfig @Bean-param + PodcastTranscriptWorker plain param), logger
  100% `private val log = LoggerFactory.getLogger(javaClass)`, comments zijn
  WHY/sectie-scheiders, `@Suppress("UNUSED_PARAMETER")` op RowMapper.map is de
  verplichte JdbcTemplate-signatuur (geen dode code). Lagen/DTO-plaatsing/KDoc al
  geconvergeerd (SF-437, SF-502).
- **Backend build warning-vrij + groen.** `mvn clean compile` → BUILD SUCCESS,
  0 warnings/deprecations. `mvn test` → 25 tests, 0 failures (4 unit-tests, geen
  PNF_DATABASE_URL nodig).
- **Frontend-/Android-spoor niet uitgevoerd, met reden (AC5).** Runner heeft geen
  flutter/dart-binary, geen gradle/Android-SDK/netwerk en geen android/local.properties
  (settings.gradle.kts faalt zonder flutter.sdk) → Android Gradle-warnings niet
  meetbaar en warning-fixes niet veilig aantoonbaar. Concreet geïdentificeerd maar
  bewust niet geforceerd: `kotlinOptions{}` → `compilerOptions{}` deprecatie in
  beide app/build.gradle.kts (versie-afhankelijke DSL; foute migratie breekt de
  Android-build, niet detecteerbaar zonder draaiende Gradle). Overgelaten aan een
  omgeving met werkende Android-toolchain. Zelfde toolchain-beperking/conclusie als
  in de vorige gelijksoortige nightly SF-343.
- **Niet recht te trekken afwijkingen gemeld (niet geforceerd):** cross-module interne
  imports en domeinmodel-als-HTTP-response (architecturale refactors, geen mechanische
  gedrag-neutrale fix); SettingsController zonder klasse-@RequestMapping (3 prefixes,
  base-path zou URLs wijzigen). Jackson com.fasterxml is inmiddels spec-conform.
- Integratietests en e2e-suite niet aangeraakt; geen openapi-/schema-/Flyway-wijziging.

Resultaat: gedrag-neutraal, lege code-diff op story-log/worklog na — geldige uitkomst.
Detail-log: docs/stories/SF-573-gedrag-neutrale-code-kwaliteit-solid-build-warnings.md

## SF-573 (reviewer) — 2026-06-28

Review akkoord. `git diff main...HEAD` bevat uitsluitend story-log + worklog (geen
code), wat de story expliciet toestaat ("kleine of lege diff is een geldige uitkomst").
Geverifieerd i.p.v. aangenomen:
- [info] Geen kale `@Value` op val/var; de 2 treffers zijn de gedocumenteerde
  uitzonderingen (`PodcastAsyncConfig` @Bean-param, `PodcastTranscriptWorker` plain param).
- [info] `SettingsController` bedient inderdaad 3 prefixes (`/api/settings`,
  `/api/rss-feeds`, `/api/podcast-feeds`); klasse-`@RequestMapping` zou URLs wijzigen —
  terecht niet aangeraakt.
- [info] Geen JSON-artefacten in story-log/worklog; bestanden eindigen correct.
- [info] Geen openapi-/schema-/Flyway-wijziging; integratie- en e2e-tests ongemoeid.
- [info] Uitgestelde Android-Gradle-deprecatie (`kotlinOptions{}` → `compilerOptions{}`)
  is met reden gemeld conform AC5; geen blocker binnen deze story.
Geen blockers/bugs gevonden.

## SF-574 (tester) — 2026-06-28

Story-brede test. Gedrag-neutrale code-kwaliteitspass met **bewust lege code-diff**
(alleen story-log + worklog) — een geldige uitkomst voor deze story. Geen browser-test
nodig: de diff raakt de frontend niet (geen Dart-/Android-wijziging), dus geen
verplichte screenshots. Geverifieerd i.p.v. aangenomen:

- **Diff is docs-only:** `git diff --name-only main...HEAD` = alleen
  `docs/stories/SF-573-...md` + `docs/stories/worklog/SF-572-worklog.md`. Geen
  `.kt`/Dart/`*.feature`/openapi/Flyway/infra. → AC1 & AC4 (integratie-/e2e-suite ongemoeid).
- **`@Value` use-site-target:** exact 2 treffers zonder `@param:`, beide de
  gedocumenteerde uitzonderingen (`PodcastAsyncConfig` @Bean-param,
  `PodcastTranscriptWorker` plain param). Geen kale `@Value` op val/var.
- **Logger:** alle ~40 `LoggerFactory.getLogger`-treffers volgen
  `private val log = LoggerFactory.getLogger(javaClass)`; geen afwijking.
- **SettingsController:** geen klasse-`@RequestMapping`; bedient 3 prefixes
  (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds` + `/event-*`-subpaden).
  Base-path zou URLs wijzigen → terecht niet aangeraakt.
- **Build-output backend:** `mvn clean compile` → BUILD SUCCESS, 0 warnings/deprecations.
  `mvn test` → Tests run: 25, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  (4 unit-test-klassen, geen DB nodig). Bevestigt AC3 (backend) en AC4.
- **Android-spoor:** terecht uitgesteld met reden (geen flutter/gradle-toolchain op
  runner); deprecatie concreet benoemd conform AC5.

Conclusie: claims kloppen, gedrag aantoonbaar ongewijzigd, build groen en warning-vrij.
Geen blockers/bugs.
