# SF-656 - nightly: Code-kwaliteit verbeteren (SOLID, build-output)

## Story

nightly: Code-kwaliteit verbeteren (SOLID, build-output)

<!-- refined-by-factory -->

## Scope
Gedrag-neutraal kwaliteits-/refactorwerk over de drie buildbare delen van de repo:

- **Backend** (Maven): `newsfeedbackend/newsfeedbackend/` — Kotlin/Spring.
- **Android-frontends** (Gradle): `frontend/android/` en `frontend-reader/android/` (beide `build.gradle.kts`).

Concrete verbeteringen:
- Code aanlijnen met SOLID-principes; leesbaarheid en onderhoudbaarheid verhogen (betere naamgeving, verwijderen van dode code, wegwerken van duplicatie, opsplitsen van te lange functies).
- Warnings en deprecations uit de build-output oplossen: Maven voor de backend, Gradle voor de Android-frontends.

Norm voor "kwaliteit" = `docs/factory/technical-spec.md` §Codeconventies + `specs/backend-technical-spec.md` (logging-niveaus, geen-comments-tenzij-WHY, `@param:Value`-target, lagen API→domain→infrastructure, Spring Modulith-modulegrenzen). API-contract: `specs/openapi.yaml`.

Buiten scope:
- Functionele wijzigingen van welke aard dan ook.
- Aanpassen van integratietests en de e2e-suite (`e2e/scenarios/`).
- Documentatie-aanvulling van `docs/factory/` (al compleet sinds SF-220) — geen docs-criterium nodig.
- Bekende, structurele afwijkingen die niet veilig gedrag-neutraal te herstellen zijn (Jackson `com.fasterxml.jackson` vs spec `tools.jackson`, cross-module domain/infrastructure-imports, domeinmodellen direct als HTTP-response): **melden** in worklog/error, niet zelf herstellen.

## Acceptance criteria
- De code is op meerdere concrete plaatsen verbeterd richting SOLID/leesbaarheid (naamgeving, dode code, duplicatie, functielengte), óf er is onderbouwd gemeld dat geen veilige gedrag-neutrale verbetering mogelijk was (kleine/lege diff is een geldige uitkomst).
- Build-warnings/deprecations in Maven-backend en beide Android Gradle-builds zijn waar veilig mogelijk opgelost; resterende, niet-veilig-oplosbare warnings zijn benoemd.
- Het **functionele gedrag is exact gelijk** gebleven.
- Alle bestaande tests slagen: `mvn test` op de backend draait groen (vereist `PNF_DATABASE_URL`).
- Integratietests en e2e-scenario's zijn **niet** aangepast. Als een refactor alleen groen te krijgen is door een integratietest te wijzigen, wordt de wijziging teruggedraaid en gaat de developer in **error** (gedragswijziging).
- Bij twijfel of een wijziging gedrag verandert: de wijziging wordt niet doorgevoerd, of de developer gaat in error.

## Aannames
- "Android-frontends" = uitsluitend de twee Gradle-modules `frontend/android/` en `frontend-reader/android/`; overige Flutter/Dart-code valt onder algemene leesbaarheidsverbeteringen maar staat niet centraal voor de build-output-eis.
- Een kleine of lege diff is acceptabel als er geen veilige, gedrag-neutrale verbeteringen zijn — dit is een nightly housekeeping-story zonder verplichte minimale wijzigingsomvang.
- `mvn test` is het primaire vangnet; in de factory-omgeving is `PNF_DATABASE_URL` beschikbaar voor de tests die dat vereisen.
- Structurele architectuurafwijkingen die de spec schendt maar niet veilig gedrag-neutraal te fixen zijn, worden gerapporteerd in het worklog en niet binnen deze story hersteld.

## Eindsamenvatting

## Eindsamenvatting — SF-656: Code-kwaliteit verbeteren (SOLID, build-output)

**Type:** nightly housekeeping — gedrag-neutraal kwaliteits-/refactorwerk over backend (Maven/Kotlin) en de twee Android Gradle-frontends.

### Wat is gebouwd / gewijzigd
Bewuste **lege code-diff**. De enige bestanden in de story-branch zijn de story-log en het worklog — geen wijziging aan implementatie, API-contract (`specs/openapi.yaml`), Flyway/schema, integratietests of e2e-suite. Een kleine/lege diff was vooraf in de acceptance criteria als geldige uitkomst benoemd.

### Belangrijkste keuzes
- **Backend (`newsfeedbackend/`)** is op alle veilig-herstelbare conventies al volledig conform (geverifieerd via grep + build): geen kale `@Value` op properties (de 2 treffers zijn gedocumenteerde, niet aan te passen parameter-uitzonderingen — `PodcastAsyncConfig` @Bean-param, `PodcastTranscriptWorker` ctor-param), loggers overal `LoggerFactory.getLogger(javaClass)`, geen inline DTO's in controllers. Geen veilige, gedrag-neutrale verbetering meer mogelijk → geen wijziging.
- **Android Gradle-frontends** zijn **bewust niet** aangepast: de Gradle/Flutter-toolchain is niet uitvoerbaar in de runner (geen flutter/dart/gradle/gradlew, geen `local.properties`), waardoor een warning-fix niet aantoonbaar veilig te verifiëren is.
- **Structurele afwijkingen gemeld i.p.v. geforceerd:** cross-module interne imports, domeinmodel-als-HTTP-response, `SettingsController` zonder klasse-`@RequestMapping` (bedient 3 prefixes). Jackson is sinds SF-502 al spec-aligned — geen afwijking meer.

### Wat is getest
- Developer, reviewer én tester draaiden zelfstandig de backend-build: `mvn clean compile` → BUILD SUCCESS, **0 warnings/deprecations**; `mvn test` → **28 tests, 0 failures/errors/skipped**.
- Reviewer: akkoord, geen blockers. Tester: **tested**, alle worklog-claims kloppen tegen de code; gedrag per constructie identiek aan `main` (doc-only diff), dus geen preview/UI-test nodig.

### Bewust niet gedaan
- Android `kotlinOptions { jvmTarget }` → `compilerOptions`-DSL-migratie (Kotlin 2.1.0, beide `app/build.gradle.kts`): geïdentificeerd maar plugin-/AGP-versie-afhankelijk en niet zonder draaiende Gradle-build veilig te valideren — overgelaten aan CI/devcontainer (zelfde conclusie als SF-343 en SF-572/573).
- Geen functionele wijzigingen; integratietests en e2e niet aangeraakt.

**Resultaat:** gedrag onveranderd, backend warning-vrij en groen — een onderbouwde, gemelde no-op voor deze nightly.
