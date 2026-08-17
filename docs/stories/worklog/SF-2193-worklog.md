# SF-2193 - Worklog

Story-context bij eerste pickup:
Vervang Pair<String, String> door AuthenticatedUser in de JWT-validatie

Voeg onderaan `newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/auth/AuthService.kt`, naast de bestaande publieke data classes `AuthToken` en `UserAccount`, toe: `data class AuthenticatedUser(val username: String, val role: String)` met een korte KDoc. Niet in `infrastructure` plaatsen: `AuthService` gebruikt het type in zijn signatuur en `ModuleStructureTest` (KNOWN_VIOLATIONS blijft leeg) faalt dan op een niet-geexposeerd type. Wijzig vervolgens: (1) `auth/AuthService.kt` - `fun validateToken(token: String): AuthenticatedUser?`; de KDoc-zin die uitlegt dat het `(username, role)` teruggeeft vervalt, de rest van de KDoc blijft. (2) `auth/domain/AuthServiceImpl.kt:69` - doorgeefregel; voeg `import com.vdzon.newsfeedbackend.auth.AuthenticatedUser` toe, dit bestand importeert elk auth-type expliciet en zonder import faalt de compile. (3) `auth/infrastructure/JwtService.kt:57-58` - retourtype `AuthenticatedUser?`, de KDoc-regel `/** Returns (username, role) ... */` vervalt, `sub to role` wordt `AuthenticatedUser(sub, role)`; ook hier is de expliciete import nodig. (4) `auth/infrastructure/JwtAuthFilter.kt:23-25` - `val (username, role) = parsed` vervalt; gebruik `parsed.username` en `parsed.role` op de plaatsen waar die waarden nu gebruikt worden (let op: `ROLE_${role.uppercase()}` blijft de autoriteit, de gebruikersnaam blijft het principal). Geen import nodig. (5) `websocket/JwtHandshakeInterceptor.kt:44` - `parsed.first` wordt `parsed.username`. Geen import nodig. Werk daarna twee documentatieplekken bij die het resultaat nog als paar beschrijven: `specs/backend-technical-spec.md:58` (auth-rij in de moduletabel) en `docs/factory/technical-spec.md:86` (`AuthService.validateToken(token)` -> `(username, role)?`). Geen gedragsverandering: validatielogica, het queryparameter-patroon van de handshake en `permitAll` in `SecurityConfig` blijven exact zoals ze zijn; `RequestWebSocketHandler.broadcast` niet hernoemen; de private Pair-vormen in `podcast/domain/PodcastScriptParser.kt:76` en `podcast_source/infrastructure/PodcastBackfillRunner.kt:76` blijven ongemoeid; `specs/openapi.yaml` en de Flutter-frontend zijn niet geraakt. Er zijn geen testwijzigingen nodig - de stub in `src/test/kotlin/com/vdzon/newsfeedbackend/websocket/JwtHandshakeInterceptorTest.kt:35` delegeert via `thenAnswer` naar `jwt.validate(...)` en verandert automatisch mee; staat er toch een testwijziging in de diff, motiveer die expliciet in het worklog. Draai `mvn -B --no-transfer-progress clean test` in `newsfeedbackend/newsfeedbackend` (BUILD SUCCESS, 129 tests groen, nul regels op het filter `warning|deprecat|self-attach|discontinued|no longer work|will be removed|in a future release`, `target/jacoco.exec` bestaat nog) en controleer dat `grep -rn "Pair<String, String>" src/main/kotlin/com/vdzon/newsfeedbackend/auth src/main/kotlin/com/vdzon/newsfeedbackend/websocket` nul treffers geeft. Sluit af met een eigen review van de diff en werk het worklog bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-2194 (development) — 2026-08-17

Uitgevoerd zoals gerefined; de story-log staat in
`docs/stories/SF-2193-Audit-vervang-Pair-in-de-JWT-validatie.md`.

Gewijzigd (7 bestanden, +15/-13):
- `auth/AuthService.kt` — `data class AuthenticatedUser(username, role)` onderaan bij
  `AuthToken`/`UserAccount`; `validateToken` geeft nu `AuthenticatedUser?`. De KDoc-zin die het
  paar uitlegde is weg, de rest van de KDoc (waarvoor de methode bedoeld is) staat er nog.
- `auth/domain/AuthServiceImpl.kt` — doorgeefregel + expliciete import (dit bestand importeert
  elk auth-type met naam; zonder import faalt de compile).
- `auth/infrastructure/JwtService.kt` — retourtype `AuthenticatedUser?`, `sub to role` wordt
  `AuthenticatedUser(sub, role)`, de KDoc-regel `/** Returns (username, role) ... */` verviel,
  plus dezelfde expliciete import.
- `auth/infrastructure/JwtAuthFilter.kt` — destructurering weg; `parsed.role` in de
  `ROLE_`-autoriteit en `parsed.username` als principal (ongewijzigd gedrag).
- `websocket/JwtHandshakeInterceptor.kt` — `parsed.first` → `parsed.username`.
- `specs/backend-technical-spec.md` (auth-rij moduletabel) en `docs/factory/technical-spec.md`
  (module-API-regel) beschrijven het resultaat niet meer als `(username, role)`-paar.

Bewust niet gedaan: geen wijziging onder `src/test/` (de stub in
`websocket/JwtHandshakeInterceptorTest.kt:35` delegeert via `thenAnswer` naar `jwt.validate(...)`
en verandert automatisch mee; het gedrag verandert niet, dus de bestaande suite is de
acceptatietest). Geen nieuwe tests: er is geen nieuw gedrag om vast te leggen, alleen een
typenaam. `RequestWebSocketHandler.broadcast` niet hernoemd, de private Pair-vormen in
`podcast/domain/PodcastScriptParser.kt` en `podcast_source/infrastructure/PodcastBackfillRunner.kt`
ongemoeid, `specs/openapi.yaml` en de Flutter-apps niet geraakt.

Vangnet: `mvn -B --no-transfer-progress clean verify` (het commando uit
`.factory/verification.yaml`; Docker was via `/var/run/docker.sock` beschikbaar) → exit 0,
129 unit + 77 e2e, 0 failures/errors, 4:48 min. `grep -rn "Pair<String, String>"` over
`auth`/`websocket` in `src/main`: nul treffers. Ruisfilter op de surefire-uitvoer: 0 regels
(op de volledige verify-log 1 regel, de bekende Nederlandse `[Podcast]`-e2e-logregel met het
woord "warnings" erin — geen regressie). `target/jacoco.exec` 372.155 B en
`target/jacoco-it.exec` 9,2 MB bestaan na afloop.

## Review SF-2194 — 2026-08-17

Akkoord. Volledige story-diff (`git diff main...HEAD`, 7 productie-/docbestanden + story-log en
worklog) nagelopen tegen de refined story en de acceptatiecriteria; alle acht kloppen:

- AC1 `grep -rn "Pair<String, String>"` over `auth`/`websocket` in `src/main` → 0 treffers.
- AC2 `AuthenticatedUser(username, role)` staat als publieke `data class` onderaan
  `auth/AuthService.kt` naast `AuthToken`/`UserAccount` en is het retourtype van zowel
  `AuthService.validateToken` als `JwtService.validate` (+ `AuthServiceImpl`-override).
- AC3 geen destructurering en geen `.first`/`.second` meer op dit resultaat: `JwtAuthFilter:25-26`
  gebruikt `parsed.role`/`parsed.username`, `JwtHandshakeInterceptor:44` `parsed.username`.
  Gedrag identiek: `ROLE_${...uppercase()}` blijft de autoriteit, de gebruikersnaam het principal.
- AC4/6 harnessbewijs `[FACTORY VERIFICATION EVIDENCE]`: `backend-maven-verify` status=passed,
  exit 0. `testedTreeSha 9af84454…` == `git rev-parse HEAD^{tree}` van de developercommit `0a6d85c`,
  dus het bewijs hoort bij déze revisie. Reports in de checkout: 129 unit + 77 e2e, geen enkel
  report zonder `Failures: 0, Errors: 0`, `ModuleStructureTest` meegedraaid en
  `KNOWN_VIOLATIONS` is nog steeds leeg. `target/jacoco.exec` en `jacoco-it.exec` bestaan.
- AC7 `specs/backend-technical-spec.md:58` en `docs/factory/technical-spec.md:86` noemen beide het
  benoemde type; nergens nog een `(username, role)`-paarbeschrijving in de specs.
- AC8 `git diff main...HEAD --name-only -- '*/src/test/*'` is leeg; de stub in
  `JwtHandshakeInterceptorTest:35` delegeert via `thenAnswer` en beweegt inderdaad automatisch mee.

Modulith: `AuthenticatedUser` staat in de module-root `com.vdzon.newsfeedbackend.auth` en is dus
geëxposeerd; `websocket` blijft via `AuthService` gaan en importeert geen `auth`-intern type.
Geen endpoint-/contractwijziging, dus `specs/openapi.yaml` terecht ongemoeid; geen Flyway-migraties.

- [suggestie] `AuthService.validateToken`-KDoc luidt nu "Valideert een JWT, of geeft `null` bij een
  ongeldig of verlopen token." Die formulering laat de succeswaarde impliciet; iets als "Valideert
  een JWT en geeft de bijbehorende [AuthenticatedUser], of `null` bij …" leest natuurlijker. Puur
  cosmetisch, geen blocker.
