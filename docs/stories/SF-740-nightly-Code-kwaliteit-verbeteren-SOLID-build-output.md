# SF-740 - nightly: Code-kwaliteit verbeteren (SOLID, build-output)

## Story

nightly: Code-kwaliteit verbeteren (SOLID, build-output)

<!-- refined-by-factory -->

## Scope
Gedrag-neutrale code-kwaliteitsverbetering (refactor) over de drie build-units van de repo. Er worden **geen** functionele wijzigingen aangebracht.

In scope:
- **SOLID / leesbaarheid / onderhoudbaarheid** in de Kotlin-backend (`newsfeedbackend/newsfeedbackend/`) en de twee Flutter-frontends (`frontend/`, `frontend-reader/`): betere naamgeving, verwijderen van dode code, wegwerken van duplicatie, opknippen van te lange functies — zonder de publieke contracten of het waarneembare gedrag te veranderen.
- **Build-output opschonen**: waarschuwingen en deprecations oplossen uit de Maven-build van de backend en uit de Gradle/Android-builds van beide frontends (`frontend/android/build.gradle.kts` en `frontend-reader/android/build.gradle.kts`).

Norm voor "kwaliteit" = `docs/factory/technical-spec.md` §Codeconventies + `specs/backend-technical-spec.md` (lagen API→domain→infrastructure, Spring Modulith-modulegrenzen, logging-niveaus, geen-comments-tenzij-WHY, `@param:`-use-site-target bij `@Value`, Jackson-groupId-conventie) en `specs/openapi.yaml` als source-of-truth voor het API-contract.

Buiten scope:
- Functionele wijzigingen, nieuwe features, gedragsveranderingen.
- Het aanpassen van integratietests / e2e-scenario's (`e2e/scenarios/`) — die vormen het vangnet.
- Het zelf "herstellen" van bekende, bewust-afwijkende patronen (zie Aannames); die worden gemeld, niet stilletjes veranderd.
- Aanvullen van `docs/factory/` (is al compleet sinds SF-220).

## Acceptance criteria
1. Het waarneembare functionele gedrag van backend en beide frontends is exact gelijk aan vóór de wijziging; geen wijzigingen aan publieke API-contracten, endpoints, responses of UI-gedrag.
2. Alle bestaande backend-tests slagen (`mvn test`, vereist een ingestelde `PNF_DATABASE_URL`); de bestaande Flutter-tests in beide frontends slagen.
3. Geen enkele integratietest of e2e-scenario (`e2e/scenarios/`) is gewijzigd. Als een refactor alleen groen te krijgen is door een integratietest/e2e-scenario aan te passen, betekent dit een gedragswijziging → de developer gaat in error in plaats van de test aan te passen.
4. De opgeloste warnings/deprecations zijn aantoonbaar verdwenen of verminderd in de build-output (Maven + beide Gradle/Android-builds), zonder nieuwe waarschuwingen te introduceren.
5. Wijzigingen sluiten aan op de codeconventies uit `docs/factory/technical-spec.md` en `specs/backend-technical-spec.md`.
6. Een kleine of zelfs lege diff is een geldige uitkomst wanneer er weinig veilig-te-verbeteren punten zijn; volume is geen doel op zich.
7. Bij twijfel of een wijziging het gedrag verandert: de wijziging wordt niet doorgevoerd, óf de developer gaat in error met een concrete toelichting.

## Aannames
- "Android-frontends" = exact de twee Flutter-apps met Android Gradle-build: `frontend/android/` en `frontend-reader/android/` (beide `build.gradle.kts`). De backend-build is Maven (`newsfeedbackend/newsfeedbackend/pom.xml`).
- `mvn test` vereist een geldige `PNF_DATABASE_URL` (Neon/Postgres); dat is een omgevingsvereiste, geen codewijziging.
- Bekende, bewust-afwijkende patronen die **niet** zelf hersteld worden maar als melding/error worden gedocumenteerd (gedragsrisico):
  - Jackson-groupId `com.fasterxml.jackson` versus een eerdere spec-vermelding van `tools.jackson` (huidige code/conventie is `com.fasterxml.jackson`).
  - Cross-module imports tussen `domain`/`infrastructure` die de Modulith-grenzen doorbreken.
  - Domeinmodellen die direct als HTTP-response worden teruggegeven.
- `docs/factory/` is al volledig ingevuld (SF-220); er wordt géén docs-aanvulling-acceptatiecriterium toegevoegd.
- Dit is een silent nightly-taak: bij echte onduidelijkheid gaat de developer in error in plaats van te wachten op input.

## Eindsamenvatting

Ik heb alle context: de story-log (SF-741), de worklogs (SF-740 developer+reviewer, SF-742 tester) en de diff. Hieronder de eindsamenvatting.

---

# Eindsamenvatting — SF-740: nightly Code-kwaliteit verbeteren (SOLID, build-output)

## Wat is gebouwd
Een **gedrag-neutrale code-kwaliteitsslag** over de drie build-units van de repo (Kotlin-backend + twee Flutter-frontends). Er zijn **geen functionele wijzigingen**: geen aanpassingen aan API-contracten, endpoints, responses of UI-gedrag.

De feitelijke code-diff is bewust klein (AC6) — de codebase bleek al grotendeels geconvergeerd uit eerdere nightlies. De enige veilig-te-verbeteren build-output was één Android-deprecation, in **beide** frontends identiek opgelost:

- `frontend/android/app/build.gradle.kts` en `frontend-reader/android/app/build.gradle.kts`: het met Kotlin Gradle Plugin 2.1.0 **deprecated** blok `kotlinOptions { jvmTarget = ... }` vervangen door de canonieke DSL `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }` (+ bijbehorende import).
- JVM-target blijft **11**; `compileOptions` ongewijzigd. Puur een DSL-modernisering, geen build- of gedragswijziging.

## Gemaakte keuzes
- **Backend (Kotlin/Maven):** geen code-diff. De src is ongewijzigd sinds SF-579; alle veilig-herstelbare conventies zijn nog conform (geverifieerd via grep: `@param:Value` met 2 gedocumenteerde uitzonderingen, logger 39/39 `javaClass`, geen inline DTO's in `*Controller.kt`). `mvn clean compile` levert geen warnings/deprecations op → build-output was al schoon.
- **Flutter/Dart:** geen code-diff; geen veilig-te-verbeteren refactor binnen de gedrag-neutrale grenzen geïdentificeerd.
- **Kleine diff als geldige uitkomst:** volume was geen doel; alleen aantoonbaar veilige, mechanische verbeteringen zijn doorgevoerd.

## Bewust niet gedaan (gemeld, geen error)
Bekende, vooraf-erkende afwijkingen zijn **niet** zelf "hersteld" omdat dat architecturale/risicovolle wijzigingen zijn (gedragsrisico):
1. Jackson-groupId `com.fasterxml.jackson` (code/spec inmiddels aligned — geen echte afwijking meer).
2. Cross-module imports tussen `domain`/`infrastructure` die Modulith-grenzen doorbreken.
3. Domeinmodellen die direct als HTTP-response worden geserialiseerd.
4. `SettingsController` zonder klasse-`@RequestMapping` (base-path toevoegen zou URL's wijzigen).

## Getest
- **Backend:** `mvn test` → BUILD SUCCESS, **28 tests groen** (geen DB nodig voor de unit-tests).
- **Diff-verificatie (reviewer + tester):** volledige story-diff = 4 bestanden (2x docs, 2x Android `build.gradle.kts`); backend-`src`, `frontend/lib`, `frontend-reader/lib` en `e2e/` ongewijzigd t.o.v. de basis. Geen integratietest of e2e-scenario aangeraakt.
- **Preview (PR #165):** app boot normaal — root HTTP 200, `/api/feed` zonder token HTTP 403 (security actief), login via Flutter-UI geslaagd, feed laadt met test-user-data. Dient als regressie-bewijs.
- **Niet lokaal te verifiëren (AC4):** de daadwerkelijke verdwijning van de Android-deprecation-warning wordt door **CI** gevalideerd — de runner heeft geen flutter/gradle/Android-SDK. Gezien de behoudende aard van de wijziging (officiële vervang-DSL, identiek JVM-target) acceptabel.

## Oordeel
Alle lokaal verifieerbare acceptatiecriteria slagen. Diff is klein, gedrag-neutraal en binnen scope; vangnet (integratie/e2e) ongemoeid. **Geen blockers, geen bugs.** AC4 wordt door CI bevestigd.
