# SF-2193 - [Audit] Vervang `Pair<String, String>` in de JWT-validatie door een benoemd resultaattype

## Story

Het resultaat van de JWT-validatie reisde als naamloze `Pair<String, String>` door de
`auth`-module en de WebSocket-handshake. Callers moesten weten dat `first` de gebruikersnaam
is en `second` de rol; dat is nergens door het typesysteem afgedwongen. Deze story vervangt
dat paar door het benoemde type `AuthenticatedUser(username, role)`.

Puur een refactor: geen gedragsverandering, geen contractwijziging (HTTP noch WebSocket).

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en `docs/factory/technical-spec.md` gelezen
- [x] `AuthenticatedUser` toegevoegd onderaan `auth/AuthService.kt`, naast `AuthToken`/`UserAccount`
- [x] `AuthService.validateToken` en `JwtService.validate` op het nieuwe retourtype gezet
- [x] `AuthServiceImpl`, `JwtAuthFilter` en `JwtHandshakeInterceptor` meegenomen (velden bij naam)
- [x] Documentatiedrift weggewerkt in `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md`
- [x] Vangnet gedraaid: `mvn -B --no-transfer-progress clean verify`
- [x] Eigen review van de diff + worklog bijgewerkt

## Gedaan en waarom

**Nieuw type.** `data class AuthenticatedUser(val username: String, val role: String)` staat
onderaan `auth/AuthService.kt` bij de andere publieke `auth`-types. Niet in `infrastructure`:
`AuthService` gebruikt het type in zijn signatuur, en `ModuleStructureTest` (Spring Modulith,
lege allowlist) faalt dan op een niet-geëxposeerd type.

**Vijf productiebestanden** (`auth/AuthService.kt`, `auth/domain/AuthServiceImpl.kt`,
`auth/infrastructure/JwtService.kt`, `auth/infrastructure/JwtAuthFilter.kt`,
`websocket/JwtHandshakeInterceptor.kt`). `JwtAuthFilter` destructureert niet meer maar leest
`parsed.role` / `parsed.username`; de handshake leest `parsed.username` in plaats van
`parsed.first`. `AuthServiceImpl` en `JwtService` kregen de expliciete import — beide bestanden
importeren elk `auth`-type met naam.

**Twee KDoc-regels vervielen** omdat ze alleen het paar uitlegden en het type dat nu zelf doet:
de `(username, role)`-zin in de `validateToken`-KDoc (de rest van die KDoc — waarvóór de
methode bedoeld is — blijft) en `/** Returns (username, role) ... */` boven `JwtService.validate`.

**Documentatie.** De `auth`-rij in `specs/backend-technical-spec.md` en de module-API-regel in
`docs/factory/technical-spec.md` beschreven het resultaat nog als `(username, role)`-paar; beide
noemen nu `AuthenticatedUser`. Historische `docs/stories/**` (o.a. SF-2165) zijn afgesloten
verslagen en blijven ongemoeid.

**Geen testwijzigingen.** De enige stub die hierop leunt
(`websocket/JwtHandshakeInterceptorTest.kt:35`) delegeert via `thenAnswer` naar `jwt.validate(...)`
en verandert automatisch mee. Het gedrag verandert niet, dus de bestaande suite ís de
acceptatietest; `git status` toont dan ook nul bestanden onder `src/test/`.

## Verificatie

`mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`: **exit 0**,
**129 unit** + **77 e2e** tests, 0 failures, 0 errors, 4:48 min (Docker was via
`/var/run/docker.sock` beschikbaar, dus de e2e-kant is lokaal gedraaid en niet aan CI overgelaten).

- `grep -rn "Pair<String, String>" src/main/kotlin/.../auth .../websocket` → **nul** treffers.
- Ruisfilter `warning|deprecat|self-attach|discontinued|no longer work|will be removed|in a future release`
  op de surefire-uitvoer: **0** regels. Op de volledige verify-log 1 regel, en dat is de bekende
  Nederlandse `[Podcast]`-logregel uit een e2e-test (bevat het woord "warnings") — geen regressie.
- `target/jacoco.exec` 372.155 B en `target/jacoco-it.exec` 9,2 MB bestaan na afloop, exact de
  bekende baseline.
