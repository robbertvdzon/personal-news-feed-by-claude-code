# SF-579 - nightly: Security: kwetsbaarheden vinden en oplossen

## Story

nightly: Security: kwetsbaarheden vinden en oplossen

<!-- refined-by-factory -->

## Scope
Voer een gerichte security-pass uit over de codebase van het Personal News Feed-project en verhelp gevonden kwetsbaarheden, zónder het functionele gedrag te wijzigen.

Te onderzoeken gebieden (bekende patronen):
- **Injectie**: SQL/JPQL, command-, header- en log-injectie; let op het samenstellen van database- en externe-API-aanroepen (backend Kotlin onder `newsfeedbackend/newsfeedbackend/`).
- **In-/uitvoervalidatie**: ontbrekende validatie op controller-DTO's en op data uit externe bronnen (RSS-feeds, Tavily, OpenAI/ElevenLabs).
- **Onveilige defaults**: te ruime CORS, permissieve security-config, ongewenst gepubliceerde actuator-endpoints, te lange/zwakke JWT-instellingen, onveilige fallback-waarden.
- **Gelekte secrets**: hardcoded sleutels/wachtwoorden/tokens in code, resources, configbestanden of `network_security_config.xml`; secrets horen via env-vars/SealedSecrets te lopen.
- **Ontbrekende authenticatie/autorisatie**: endpoints die beveiligd horen te zijn maar publiek staan, of admin-only-endpoints (`/api/admin/**`, `ROLE_ADMIN`) zonder correcte rolcheck; controleer tegen de publieke-paden-lijst in de security-config.
- **Verouderde/kwetsbare dependencies**: backend Maven (`pom.xml`) en de Flutter-frontends (`frontend/`, `frontend-reader/`).

Afbakening:
- Gedrag-neutrale fixes (defensieve hardening, validatie toevoegen die geen geldige flow blokkeert, dependency-bumps zonder gedragswijziging) mogen worden doorgevoerd.
- Grote of risicovolle wijzigingen die het functionele gedrag zouden veranderen, worden niet zelf doorgevoerd: de developer gaat in error met een concrete beschrijving zodat een mens het oppakt.
- Documentatie onder `docs/factory/` is al compleet (SF-220); er hoeft geen docs-aanvulling-acceptatiecriterium te worden toegevoegd.

## Acceptance criteria
1. De aangewezen security-gebieden zijn nagelopen; gevonden kwetsbaarheden zijn ofwel verholpen met een gedrag-neutrale fix, ofwel — bij risico op gedragswijziging — gemeld via error met concrete onderbouwing.
2. Het functionele gedrag blijft exact hetzelfde; geen API-contract- of gedragswijziging (`specs/openapi.yaml` blijft leidend en consistent).
3. Alle bestaande tests blijven slagen (`mvn test` groen; vereist `PNF_DATABASE_URL`). De bestaande unit-tests en de e2e/integratie-suite worden niet aangepast.
4. De integratie-/e2e-suite (vangnet) wordt niet gewijzigd. Als een fix alleen groen te krijgen is door een integratietest aan te passen, betekent dat een gedragswijziging → in error gaan in plaats van de test wijzigen.
5. Er worden geen nieuwe secrets in code/output geïntroduceerd; bestaande hardcoded secrets (indien gevonden) worden naar de juiste secret-mechanismen verplaatst.
6. Een lege of kleine diff is een geldige uitkomst wanneer geen veilig-oplosbare kwetsbaarheden worden gevonden; resterende of risicovolle bevindingen worden in het worklog/error gerapporteerd.

## Aannames
- De backend onder `newsfeedbackend/newsfeedbackend/` bevat de relevante security-infrastructuur: `auth/infrastructure/SecurityConfig.kt`, `JwtAuthFilter.kt`, `AuthServiceImpl.kt` (BCrypt). JWT is HS256, 30 dagen geldig; publieke paden: `/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**`, `/actuator/**`; admin-only: `/api/admin/**`.
- De backend heeft (stand 2026-06-28) geen draaiende backend-integratietest-infra, alleen unit-tests; "integratietests/vangnet" verwijst praktisch naar de bestaande unit-tests en de markdown-e2e-suite onder `e2e/scenarios/`. Geen van beide wordt aangepast.
- Dit is een silent nightly job: bij echte onduidelijkheid of risico op gedragswijziging gaat de developer in error in plaats van te gokken of te wachten.
- Bekende, bewust-geaccepteerde architectuurafwijkingen (bv. Jackson `com.fasterxml` vs. `tools.jackson`, cross-module imports, domeinmodel-als-HTTP-response) vallen buiten deze security-story en worden niet onder de noemer "kwetsbaarheid" herschreven.
- Dependency-updates beperken zich tot bumps zonder gedrags-/API-wijziging; major-upgrades met gedragsrisico worden gemeld in plaats van doorgevoerd.

## Eindsamenvatting

## Eindsamenvatting — SF-579: Security: kwetsbaarheden vinden en oplossen

### Wat is gebouwd
Een gerichte, **gedrag-neutrale security-pass** over de codebase (Kotlin/Spring-backend + Flutter-frontends). De volledige bekende patroonlijst is nagelopen; daar kwam **één concrete, veilig-oplosbare kwetsbaarheid** uit, die is verholpen:

- **Argument-injectie naar `yt-dlp` afgedicht** in `events/infrastructure/VideoAudioDownloader.kt`. De video-URL (deels gebruiker-/AI-beïnvloedbaar via `POST /api/events/{id}/videos/summarize` en Tavily-discovery) werd als positioneel argument aan het `yt-dlp`-binary doorgegeven. Een waarde die met `-` begint kon door yt-dlp als **vlag** worden geïnterpreteerd. Fix: de standaard `--` end-of-options-separator vlak vóór de URL geplaatst, en de argumentenlijst verhuisd naar een testbare `buildArgs(...)`. Klassieke command-injectie was al niet mogelijk (`ProcessBuilder` draait zonder shell); dit dicht de resterende argument-injectie-vector af.

### Gemaakte keuzes
- **Strikt gedrag-neutraal**: voor elke geldige http(s)-URL is de yt-dlp-aanroep identiek; alleen invoer die anders als vlag zou gelden, gaat nu als (ongeldige) URL door. Geen API-contract- of responswijziging; `specs/openapi.yaml` ongewijzigd.
- **Bewust níét doorgevoerd** (risico op gedragswijziging → gemeld i.p.v. gewijzigd, conform AC 1/6):
  - **CORS-wildcard** (`addAllowedOriginPattern("*")`): veilig omdat auth via `Bearer`-header loopt (geen cookies/credentials); aanscherpen zou multi-host-toegang (web/APK/LAN) breken.
  - **Actuator `health.show-details=always`**: verbergen zou de health-respons wijzigen.
  - **JWT dev-fallback secret**: placeholder in `application.properties`, in productie overschreven via env `APP_JWT_SECRET` (SealedSecret). Geen echt geheim in de repo.
  - **Android cleartext-config**: intentioneel, gescoped tot vaste home-IP + localhost.

### Nagelopen, geen wijziging nodig
SQL/JPQL-injectie (overal `NamedParameterJdbcTemplate` met benoemde parameters), overige `ProcessBuilder`-calls (`Mp3Concatenator`, `AudioTranscoder` — alleen interne temp-paden), authn/authz (publieke paden + admin-only komen overeen met `SecurityConfig.kt` en `openapi.yaml`), gelekte secrets (alle keys via `${PNF_*}`-env-vars), en dependencies (geen veilige gedrag-neutrale bump nodig).

### Wat is getest
- **28 tests groen** (25 bestaand + 3 nieuw) volgens developer; tester verifieerde de nieuwe `VideoAudioDownloaderArgsTest` (**3/3 groen**, BUILD SUCCESS, JDK 21 / Kotlin 2.2.21).
- De volledige Cucumber-`mvn test` is door de tester niet zelf gedraaid (vereist gedeelde DB met destructief risico) — bewust geïsoleerd op de DB-vrije testklasse, conform tester-conventie.
- Bestaande unit-tests en de e2e-suite onder `e2e/scenarios/` zijn **niet** aangepast (AC 3/4). Worklog-claims steekproefsgewijs met grep geverifieerd.
- Reviewer akkoord: fix bevestigd gedrag-neutraal, geen modulith-overtreding, geen contract-/Flyway-wijziging.

### Wat bewust niet is gedaan
Geen CORS-restrictie, geen actuator-detailverberging, geen JWT-secret-rotatie, geen dependency-bumps en geen Android-cleartext-wijziging — allemaal vanwege gedragsrisico, expliciet onderbouwd in het worklog. Een kleine diff is hier een geldige uitkomst (AC 6).

**Eindresultaat:** één gedrag-neutrale security-hardening + bijbehorende unit-test; alle aangewezen security-gebieden nagelopen en bevindingen onderbouwd.
