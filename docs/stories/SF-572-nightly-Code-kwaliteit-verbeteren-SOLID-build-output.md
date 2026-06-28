# SF-572 - nightly: Code-kwaliteit verbeteren (SOLID, build-output)

## Story

nightly: Code-kwaliteit verbeteren (SOLID, build-output)

<!-- refined-by-factory -->

## Scope
Gedrag-neutraal verbeteren van de codekwaliteit, zonder enige functionele verandering.

- **SOLID & leesbaarheid (backend Kotlin, `newsfeedbackend/newsfeedbackend/`):** kleine, veilige refactors die de SOLID-principes versterken en de onderhoudbaarheid verhogen: betere naamgeving, verwijderen van dode code, wegwerken van duplicatie en opsplitsen van te lange functies/klassen. Volg de bestaande conventies uit `docs/factory/technical-spec.md` (§Codeconventies: geen comments tenzij WHY, `@param:`-target bij `@Value`, lagen API→domain→infrastructure) en `specs/backend-technical-spec.md` (Spring Modulith-modulegrenzen).
- **SOLID & leesbaarheid (frontends):** dezelfde soort gedrag-neutrale opschoning in `frontend/` en `frontend-reader/` waar dat aantoonbaar veilig is.
- **Build-output opschonen:**
  - Maven-backend: warnings en deprecations uit `mvn -DskipTests package` / `mvn test` oplossen.
  - Beide Android-frontends (`frontend/android/` en `frontend-reader/android/`): warnings en deprecations uit de Gradle-build oplossen.
- Buiten scope: nieuwe features, gedragswijzigingen, aanpassen van integratietests of de e2e-suite, en het optuigen van nieuwe testinfrastructuur.

## Acceptance criteria
1. Het functionele gedrag is exact ongewijzigd; er zijn geen API-contract- (`specs/openapi.yaml`) of gedragswijzigingen.
2. Aantoonbare kwaliteitsverbeteringen in de aangeraakte code (SOLID, naamgeving, dode code, duplicatie, functielengte) conform de codeconventies in `docs/factory/technical-spec.md` en `specs/backend-technical-spec.md`.
3. Opgeloste build-warnings/deprecations in de Maven-backend én in beide Android Gradle-builds; resterende warnings die niet veilig op te lossen zijn worden in de PR/het worklog benoemd met reden.
4. Alle bestaande tests blijven slagen (`mvn test`); de integratietests en de e2e-suite zijn **niet** gewijzigd.
5. Als een gewenste refactor of warning-fix niet veilig (gedrag-neutraal) kan worden doorgevoerd, wordt die niet gedaan en concreet gemeld i.p.v. geforceerd.

## Aannames
- "Android-frontends" = `frontend/android/` en `frontend-reader/android/`; de backend-build is Maven onder `newsfeedbackend/newsfeedbackend/`.
- Een kleine of gerichte diff is een geldige uitkomst; volledigheid is geen doel — veiligheid (gedrag-neutraal) gaat voor omvang.
- Moet een integratietest of e2e-scenario worden aangepast om de refactor groen te krijgen, dan verandert het gedrag → de developer gaat in error i.p.v. de test te wijzigen.
- Bekende, niet-veilig-te-herstellen afwijkingen (bv. Jackson `com.fasterxml.jackson` vs spec `tools.jackson`, cross-module domain/infrastructure-imports, domeinmodellen direct als HTTP-response) worden gemeld, niet binnen deze story rechtgetrokken.
- `docs/factory/` is reeds compleet; er hoeft geen documentatie te worden aangevuld als onderdeel van deze story.

## Eindsamenvatting

Eindsamenvatting voor de PO van story **SF-572 — nightly: Code-kwaliteit verbeteren (SOLID, build-output)**.

---

## Eindsamenvatting SF-572

**Doel:** gedrag-neutraal de codekwaliteit verbeteren (SOLID, naamgeving, dode code, duplicatie, functielengte) en build-warnings/deprecations wegwerken — zonder enige functionele wijziging. Een kleine of lege diff was vooraf als geldige uitkomst benoemd.

### Wat is gebouwd
Geen code-wijziging. De uitkomst is een **bewust lege code-diff** (alleen story-log + worklog). Uit verificatie bleek dat er geen veilige, gedrag-neutrale verbetering meer aan te brengen was:

- **Backend (Kotlin, `newsfeedbackend/`)** is al volledig conform de conventies. Geverifieerd: geen kale `@Value` op val/var (de 2 treffers zijn gedocumenteerde, terecht uitgezonderde gevallen), logger overal `private val log = LoggerFactory.getLogger(javaClass)`, comments zijn WHY/sectie-scheiders, geen dode code (`@Suppress("UNUSED_PARAMETER")` is de verplichte JdbcTemplate-`RowMapper`-signatuur). Lagen/DTO-plaatsing al geconvergeerd in eerdere stories.
- **Backend build is schoon en groen:** `mvn clean compile` → BUILD SUCCESS, 0 warnings/deprecations.

### Gemaakte keuzes
- **Frontend-/Android-spoor bewust niet uitgevoerd (conform AC5).** De runner heeft geen flutter/dart-binary, geen gradle/Android-SDK/netwerk en geen `android/local.properties`, waardoor de Android Gradle-build niet draait en warning-fixes niet veilig aantoonbaar zijn. Eén concrete deprecatie is geïdentificeerd maar niet geforceerd: `kotlinOptions{}` → `compilerOptions{}` in beide `app/build.gradle.kts` — een plausibele maar versie-afhankelijke migratie die de release-build kan breken en niet zonder draaiende Gradle te verifiëren is. Doorgeschoven naar een omgeving met werkende Android-toolchain (zelfde beperking/conclusie als de eerdere nightly SF-343).
- **Bekende afwijkingen gemeld, niet rechtgetrokken:** cross-module interne imports en domeinmodel-als-HTTP-response (architecturale refactors, niet mechanisch gedrag-neutraal); `SettingsController` zonder klasse-`@RequestMapping` (bedient 3 prefixes, base-path zou URLs wijzigen). Jackson `com.fasterxml` is inmiddels spec-conform — geen afwijking meer.

### Wat is getest
- Tester bevestigde **docs-only diff** (geen `.kt`/Dart/feature/openapi/Flyway/infra) → AC1 & AC4 geborgd.
- `mvn test` → **25 tests, 0 failures/errors/skips — BUILD SUCCESS**.
- Backend warning-vrij; conventies (`@Value`, logger, controller-routing) handmatig geverifieerd.
- Integratietests en e2e-suite ongemoeid. Reviewer en tester: geen blockers/bugs.

### Bewust niet gedaan
Frontend/Android-warningfixes, architecturale refactors en de Jackson-migratie — allemaal met concrete reden gemeld i.p.v. onveilig geforceerd.

**Resultaat:** gedrag-neutraal, geen API-/schema-/contractwijziging, build groen en warning-vrij. Geldige uitkomst voor deze story.

---
