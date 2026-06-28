# SF-579 - Worklog

Story-context bij eerste pickup:
Security-pass: kwetsbaarheden vinden en gedrag-neutraal oplossen

Voer een gerichte, gedrag-neutrale security-pass uit over de codebase (backend Kotlin/Spring onder newsfeedbackend/newsfeedbackend/ en Flutter frontends frontend/ + frontend-reader/). Loop de bekende patronen na: (1) injectie (JPQL/SQL, command-/header-/log-injectie, externe-API-aanroepen RSS/Tavily/OpenAI/ElevenLabs); (2) ontbrekende in-/uitvoervalidatie op controller-DTO's en externe data; (3) onveilige defaults (te ruime CORS, permissieve security-config, gepubliceerde actuator-endpoints, zwakke/te lange JWT-instellingen, onveilige fallbacks); (4) gelekte secrets (hardcoded sleutels/tokens in code/resources/config/network_security_config.xml -> via env-vars/SealedSecrets); (5) ontbrekende authn/authz (publieke vs. beveiligde endpoints, admin-only /api/admin/** ROLE_ADMIN), vergelijk tegen publieke-paden-lijst in SecurityConfig.kt en tegen specs/openapi.yaml; (6) verouderde/kwetsbare dependencies (pom.xml + Flutter), alleen bumps zonder gedrags-/API-wijziging. Verhelp veilig-oplosbare bevindingen met gedrag-neutrale fixes; voeg waar zinvol unit-tests toe.

## Stappenplan

[x]: read issue and target docs
[x]: security-pass uitvoeren over de bekende patronen
[x]: veilig-oplosbare bevinding(en) gedrag-neutraal fixen + unit-test toevoegen
[x]: relevante tests draaien (mvn test)
[x]: story-log/worklog bijwerken met resultaten

## Doorgevoerde fix (gedrag-neutraal)

### Argument-injectie naar `yt-dlp` afgedicht — `events/infrastructure/VideoAudioDownloader.kt`
- **Bevinding**: `download(username, videoUrl)` gaf `videoUrl` als laatste positionele
  argument door aan het `yt-dlp`-binary (`ProcessBuilder`). De `videoUrl` is
  uiteindelijk afkomstig uit externe AI/Tavily-discovery-output en is via
  `POST /api/events/{id}/videos/summarize` (`VideoSummaryRequest.videoUrl`)
  ook deels gebruiker-beïnvloedbaar. Een waarde die met `-` begint zou door
  yt-dlp als **optie/vlag** worden geïnterpreteerd i.p.v. als URL
  (argument-injectie). `ProcessBuilder` met een arg-lijst gebruikt géén shell,
  dus klassieke command-injectie was al niet mogelijk; dit dicht de resterende
  argument-injectie-vector af.
- **Fix**: de standaard `--` end-of-options-separator vlak vóór de URL geplaatst,
  zodat yt-dlp alles ná `--` als positioneel argument behandelt. De
  argumentenlijst is daarbij verhuisd naar een testbare `buildArgs(...)` in een
  companion object.
- **Waarom gedrag-neutraal**: voor elke geldige `http(s)`-URL levert yt-dlp met
  `-- <url>` exact dezelfde aanroep/uitvoer op; alleen invoer die anders als vlag
  zou zijn geïnterpreteerd wordt nu als (ongeldige) URL doorgegeven. Geen
  API-contract- of responsewijziging.
- **Test**: `events/VideoAudioDownloaderArgsTest.kt` (3 tests) borgt dat `--`
  direct vóór de URL staat, dat een met `-` beginnende waarde achter de separator
  belandt, en dat binary + output-template ongewijzigd blijven.

## Nagelopen, géén wijziging nodig (bevindingen/onderbouwing)

1. **SQL/JPQL-injectie**: alle repositories (`auth`, `rss`, `feed`, `events`,
   `settings`, `podcast*`) gebruiken `NamedParameterJdbcTemplate` met
   benoemde parameters (`:u`, `:id`, ...). Geen string-concatenatie van
   gebruikersinvoer in queries → geen SQL-injectie.
2. **Command-injectie**: alle drie `ProcessBuilder`-gebruiken
   (`Mp3Concatenator`, `AudioTranscoder`, `VideoAudioDownloader`) draaien
   zónder shell met een expliciete arg-lijst. ffmpeg-calls gebruiken
   uitsluitend interne temp-paden (geen externe invoer). Alleen de yt-dlp-URL
   was externe invoer → hierboven afgedicht.
3. **JWT-config**: HS256, secret-lengte ≥32 bytes wordt afgedwongen
   (`require(bytes.size >= 32)`); ttl via `app.jwt.ttl-days:30`. De
   placeholder-default `app.jwt.secret=change-me-...` in
   `application.properties` is uitsluitend een **dev-fallback**: in productie
   wordt deze via env `APP_JWT_SECRET` (SealedSecret-key `JWT_SECRET`,
   `deploy/base/backend-deployment.yaml`) overschreven door Spring relaxed
   binding. Geen echt geheim in de repo; env-var-mechanisme is al aanwezig.
   Niet gewijzigd (zou alleen schijn-hardening zijn).
4. **CORS**: `addAllowedOriginPattern("*")` zónder `allowCredentials(true)`.
   Auth loopt via een `Authorization: Bearer`-header (geen cookies/credentials),
   dus de wildcard-origin lekt geen credential-context. Aanscherpen tot een
   vaste origin-lijst zou de huidige multi-host-toegang (web + APK + LAN)
   breken → gedragswijziging, niet doorgevoerd.
5. **Actuator**: `management.endpoints.web.exposure.include=health,prometheus,info`
   (geen `*`), `/actuator/**` is publiek (`permitAll`). `health.show-details=always`
   toont detail; verbergen (`when-authorized`) wijzigt de health-respons →
   gedragswijziging, gemeld i.p.v. doorgevoerd.
6. **Android cleartext** (`frontend/.../network_security_config.xml`):
   bewust toegestaan cleartext-HTTP naar één vaste home-IP + localhost/emulator,
   gedocumenteerd en scoped (`base-config` blijft HTTPS-only). Intentioneel
   ontwerp, niet gewijzigd.
7. **Authn/authz**: publieke paden in `SecurityConfig.kt`
   (`/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**`, `/actuator/**`)
   en admin-only `/api/admin/**` (`hasRole("ADMIN")`) komen overeen met
   `specs/openapi.yaml` (`security: []` op de shared-/auth-/version-paden,
   `ROLE_ADMIN` op admin). Geen ongedekt-publiek beveiligd endpoint gevonden.
8. **Gelekte secrets**: geen hardcoded API-keys/tokens in code/resources/frontend;
   alle provider-keys lopen via `${PNF_*:}`-env-vars. De SealedSecret-waarden in
   `deploy/base/sealed-secret-api-keys.yaml` zijn versleuteld (by-design openbaar).
9. **Dependencies**: geen veilige, gedrag-neutrale bump geïdentificeerd die
   nodig was; major-upgrades met gedragsrisico vallen buiten scope (per AC).

## Resultaat

- `mvn test`: **28 tests groen** (25 bestaand + 3 nieuw). Bestaande unit-tests en
  de e2e-suite onder `e2e/scenarios/` zijn niet aangepast.
- Diff: één gedrag-neutrale hardening + bijbehorende unit-test. `specs/openapi.yaml`
  ongewijzigd en consistent (geen API-contractwijziging).

## Review (SF-580, reviewer)

[info] Volledige story-diff t.o.v. `main` beoordeeld: alleen `VideoAudioDownloader.kt`
(fix), `VideoAudioDownloaderArgsTest.kt` (3 tests) en dit worklog gewijzigd.
[info] Fix bevestigd gedrag-neutraal: `--` is de standaard end-of-options-separator;
voor geldige http(s)-URL's (niet beginnend met `-`) is de yt-dlp-aanroep identiek.
Geen legitieme flow geblokkeerd — een met `-` beginnende waarde is geen geldige
video-URL en zou voorheen al als (onbekende) vlag falen.
[info] Verificatie overige `ProcessBuilder`-calls (`Mp3Concatenator`, `AudioTranscoder`):
gebruiken uitsluitend intern aangemaakte temp-paden (`*.absolutePath`), geen externe
invoer → terecht niet aangepast.
[info] Geen API-/contractwijziging, geen Spring Modulith-moduleovertreding (alles binnen
`events`-module + companion), geen Flyway-migratie, geen story-log JSON-artefacten.
[info] Geaccepteerde/gemelde niet-fixbare items (CORS-wildcard, actuator `show-details`,
JWT dev-fallback, Android cleartext) zijn correct onderbouwd als gedragsrisico → melden
i.p.v. doorvoeren, conform AC 1/6. Akkoord.
