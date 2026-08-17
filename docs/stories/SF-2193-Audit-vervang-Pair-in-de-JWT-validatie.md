# SF-2193 - [Audit] Audit: vervang Pair<String, String> in de JWT-validatie door een benoemd resultaattype

## Story

[Audit] Audit: vervang Pair<String, String> in de JWT-validatie door een benoemd resultaattype

<!-- refined-by-factory -->

## Scope

Vervang het naamloze `Pair<String, String>` in de JWT-validatie door een benoemd resultaattype in de `auth`-module.

**Nieuw type** — in `auth/AuthService.kt`, onderaan naast de bestaande publieke data classes `AuthToken` en `UserAccount` (dat is de bestaande huisstijl voor publieke `auth`-types; `infrastructure` is geen optie omdat `AuthService` het type in zijn signatuur gebruikt en `ModuleStructureTest` dan faalt op een niet-geëxposeerd type):

```kotlin
/** De geauthenticeerde gebruiker achter een geldig JWT. */
data class AuthenticatedUser(val username: String, val role: String)
```

**Te wijzigen productiebestanden (5):**

- `auth/AuthService.kt` — `fun validateToken(token: String): AuthenticatedUser?`; de KDoc-zin die uitlegt dat het `(username, role)` teruggeeft kan weg, de rest van de KDoc (waarvoor de methode bedoeld is) blijft. Het nieuwe type erbij.
- `auth/domain/AuthServiceImpl.kt:69` — doorgeefregel; **let op:** dit bestand importeert elk `auth`-type expliciet, dus `import com.vdzon.newsfeedbackend.auth.AuthenticatedUser` toevoegen, anders faalt de compile.
- `auth/infrastructure/JwtService.kt:57-58` — retourtype; de KDoc-regel `/** Returns (username, role) of een geldige token, of null. */` vervalt, `sub to role` wordt `AuthenticatedUser(sub, role)`. Ook hier is een expliciete import nodig.
- `auth/infrastructure/JwtAuthFilter.kt:23-25` — `val (username, role) = parsed` vervalt; gebruik `parsed.username` / `parsed.role` op de plaatsen waar die waarden nu gebruikt worden. Geen import nodig (type-inferentie).
- `websocket/JwtHandshakeInterceptor.kt:44` — `parsed.first` wordt `parsed.username`. Geen import nodig.

**Documentatie (2 plekken):**

- `specs/backend-technical-spec.md:58` — de `auth`-rij in de moduletabel beschrijft `AuthService.validateToken(token)` als "geeft `(username, role)`"; werk dat bij naar het benoemde type.
- `docs/factory/technical-spec.md:86` — bevat dezelfde formulering `AuthService.validateToken(token)` → `(username, role)?`; werk ook die bij. (Deze plek staat niet in de oorspronkelijke aanleiding maar valt onder dezelfde drift.)

**Tests:** er zijn geen testwijzigingen nodig. De stub in `src/test/.../websocket/JwtHandshakeInterceptorTest.kt:35` delegeert via `thenAnswer` naar `jwt.validate(...)`, dus die verandert automatisch mee met het nieuwe type. Dit is geverifieerd: de volledige suite draait groen zonder één testregel aan te raken. Nieuwe tests zijn niet nodig — het gedrag verandert niet, de bestaande suite ís de acceptatietest.

**Bewust buiten scope**

- Geen enkele gedragsverandering: de validatie zelf, het queryparameter-patroon van de handshake en de `permitAll` in `SecurityConfig` blijven exact zoals ze zijn.
- `RequestWebSocketHandler.broadcast` niet hernoemen (eigen story waard, raakt ~6 documentatieplekken).
- De overige `Pair<String, String>`-voorkomens in de backend (`podcast/domain/PodcastScriptParser.kt:76`, `podcast_source/infrastructure/PodcastBackfillRunner.kt:76`) zijn private/lokale hulpvormen buiten de `auth`-grens en blijven ongemoeid.
- Frontend (Flutter) en `specs/openapi.yaml`: niet geraakt — het HTTP-/WebSocket-contract verandert niet.

## Acceptance criteria

1. `grep -rn "Pair<String, String>" src/main/kotlin/com/vdzon/newsfeedbackend/auth src/main/kotlin/com/vdzon/newsfeedbackend/websocket` (vanuit `newsfeedbackend/newsfeedbackend`) geeft **nul** treffers.
2. `AuthenticatedUser` is een `data class` met de velden `username` en `role`, gedefinieerd in `auth/AuthService.kt`, en is het retourtype van zowel `AuthService.validateToken` als `JwtService.validate`.
3. `JwtAuthFilter` en `JwtHandshakeInterceptor` lezen de velden bij naam (`parsed.username` / `parsed.role`); er staat geen destructurering of `.first`/`.second` meer op dit resultaat.
4. `mvn -B --no-transfer-progress clean test` in `newsfeedbackend/newsfeedbackend`: BUILD SUCCESS met **129** tests groen, waaronder `ModuleStructureTest` (`KNOWN_VIOLATIONS` blijft leeg).
5. De build-uitvoer geeft **nul** regels op het filter `warning|deprecat|self-attach|discontinued|no longer work|will be removed|in a future release` (case-insensitive).
6. `target/jacoco.exec` bestaat na afloop nog steeds.
7. Noch `specs/backend-technical-spec.md` noch `docs/factory/technical-spec.md` beschrijft het resultaat van `validateToken` nog als `(username, role)`-paar; beide noemen het benoemde type.
8. Er zijn geen wijzigingen in `src/test/` nodig; als er tóch een testwijziging in de diff staat, is die expliciet gemotiveerd.

## Aannames

- **Typenaam** `AuthenticatedUser` zoals in de aanleiding voorgesteld; alternatieve namen zijn niet nodig, er is geen bestaande botsing in de `auth`-module.
- **Plaatsing** onderaan `auth/AuthService.kt` naast `AuthToken`/`UserAccount` in plaats van een apart bestand — dat volgt de bestaande vorm van dat bestand.
- De regelverwijzingen in deze story zijn gecontroleerd tegen `ai/SF-2193` (HEAD `6c6e7a4`); de oorspronkelijke aanleiding noemde `JwtAuthFilter.kt:22-24` en `JwtHandshakeInterceptor.kt:41`, dat zijn in werkelijkheid `:23-25` en `:44`.
- Baseline gemeten op deze branch: 129 tests groen, nul ruisregels, `target/jacoco.exec` ≈ 372 KB. De refactor is proefondervindelijk uitgevoerd in een wegwerpkopie en houdt alle drie die waarden intact.
- E2E (failsafe/Testcontainers) is in de agent-container niet draaibaar (geen Docker); dat deel wordt door CI (`.factory/verification.yaml`) bevestigd. Geen enkel e2e-bestand refereert aan `validateToken`, dus daar wordt geen impact verwacht.

## Eindsamenvatting

Ik heb `.task.md`, de story-log, het worklog en de volledige story-diff (`git diff main...HEAD`) gelezen. Hieronder de eindsamenvatting.

## SF-2193 — Vervang `Pair<String, String>` in de JWT-validatie door een benoemd resultaattype

**Wat is gebouwd**

Het resultaat van de JWT-validatie reisde als naamloze `Pair<String, String>` door de `auth`-module en de WebSocket-handshake; callers moesten zelf weten dat `first` de gebruikersnaam is en `second` de rol. Dat paar is vervangen door het benoemde type `data class AuthenticatedUser(val username: String, val role: String)`.

Gewijzigd (5 productiebestanden, +15/-13):
- `auth/AuthService.kt` — nieuw type onderaan bij `AuthToken`/`UserAccount`; `validateToken` geeft nu `AuthenticatedUser?`.
- `auth/domain/AuthServiceImpl.kt` en `auth/infrastructure/JwtService.kt` — retour-/doorgeeftype plus expliciete import (deze bestanden importeren elk `auth`-type met naam).
- `auth/infrastructure/JwtAuthFilter.kt` — destructurering weg, leest `parsed.role` / `parsed.username`.
- `websocket/JwtHandshakeInterceptor.kt` — `parsed.first` → `parsed.username`.

Documentatiedrift weggewerkt op de twee plekken die het resultaat nog als `(username, role)`-paar beschreven: `specs/backend-technical-spec.md` (auth-rij moduletabel) en `docs/factory/technical-spec.md` (module-API-regel).

**Keuzes**
- Type in de module-root `com.vdzon.newsfeedbackend.auth` en niet in `infrastructure`: `AuthService` gebruikt het in zijn signatuur, en `ModuleStructureTest` (Spring Modulith, lege allowlist) zou anders falen op een niet-geëxposeerd type.
- Twee KDoc-regels die alleen het paar uitlegden zijn verwijderd — het type documenteert dat nu zelf.
- Geen testwijzigingen: de enige stub die hierop leunt (`JwtHandshakeInterceptorTest:35`) delegeert via `thenAnswer` en beweegt automatisch mee. Er is geen nieuw gedrag, dus de bestaande suite ís de acceptatietest.

**Wat is getest**
- `mvn clean verify` bij de developercommit: exit 0, 129 unit + 77 e2e tests, 0 failures/errors. Tester-herhaling met `mvn clean test`: 129 tests groen, 0 ruisregels op het waarschuwingsfilter, `target/jacoco.exec` op de bekende baseline (372.155 B). Bewijs gekoppeld aan de juiste revisie via `testedTreeSha`.
- Statische criteria opnieuw geverifieerd: nul `Pair<String, String>`-treffers in `auth`/`websocket` onder `src/main`, geen `.first`/`.second`/destructurering meer, geen `src/test/`-wijzigingen in de diff.
- Live op de preview: HTTP-auth (200 met geldig token, 403 zonder/met kapot token, admin-endpoint 403 voor een user-token) en de WebSocket-handshake (opent met geldig token, geweigerd zonder; user-scoped frames bevestigen de juiste gebruikersnaam in `ATTR_USERNAME`). Ook een Flutter-UI-login end-to-end. Wegwerp-testaccount na afloop verwijderd.

**Bewust niet gedaan**
- Geen enkele gedragswijziging: validatielogica, het queryparameter-patroon van de handshake en `permitAll` in `SecurityConfig` blijven exact zoals ze waren; geen contractwijziging (HTTP noch WebSocket), dus `specs/openapi.yaml` en de Flutter-apps zijn niet geraakt.
- `RequestWebSocketHandler.broadcast` niet hernoemd (aparte story waard).
- De private/lokale `Pair`-vormen in `PodcastScriptParser` en `PodcastBackfillRunner` blijven ongemoeid — buiten de `auth`-grens.
- Eén cosmetische reviewsuggestie (de `validateToken`-KDoc expliciet het succesresultaat laten noemen) is niet doorgevoerd; geen blocker.

**Aandachtspunt (geen bevinding):** tijdens de test deed de preview een rolling restart naar de reviewercommit, waardoor één rerun-verzoek op PENDING bleef staan. Die commit bevat alleen worklog-tekst, dus het testbewijs blijft geldig voor HEAD.
