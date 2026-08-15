# SF-2166 — WebSocket `/ws/requests` authenticeren en per eigenaar leveren

Story SF-2165: de WebSocket `/ws/requests` stond op `permitAll` en stuurde
elk `NewsRequest` (met `subject`, `extraInstructions`, `sourceItemTitle`,
`categoryResults`) naar álle verbonden clients. Deze subtaak sluit dat lek
via autorisatie: de handshake authenticeert en de levering gaat alleen naar
de eigenaar.

## Stappenplan

- [x] Issue, factory-docs en `specs/backend-technical-spec.md` gelezen
- [x] Backend: `JwtHandshakeInterceptor` + registratie in `WebSocketConfig`
- [x] Backend: `broadcast(username, payload)` en de twee callers aangepast
- [x] Backend: unit-tests voor interceptor en per-eigenaar-levering
- [x] Backend: e2e-tests (token op de handshake, twee gebruikers, weigeringen)
- [x] Frontend: token mee bij connect en reconnect; niet verbinden zonder token
- [x] Frontend: socket sluit bij logout en gaat opnieuw op met het nieuwe token
- [x] Frontend: tests voor de ws-url en de logout-invalidatie
- [x] Vangnet gedraaid: `mvn clean verify` + `flutter test`

## Wat is er gedaan en waarom

**Backend**

- Nieuw `websocket/JwtHandshakeInterceptor.kt`: leest het JWT uit
  queryparameter `token` (een browser-WebSocket kan geen
  `Authorization`-header zetten — zelfde precedent als het audio-endpoint in
  `JwtAuthFilter`), valideert het en zet de gebruikersnaam in
  `attributes["username"]`. Ontbreekt of faalt het token, dan gaat de
  responsestatus op `401` en geeft `beforeHandshake` `false` terug: er komt
  geen sessie tot stand en dus ook geen `serverVersion`-bericht.
- `WebSocketConfig` registreert de interceptor achter
  `registry.addHandler(handler, "/ws/requests")`. `SecurityConfig` en
  `setAllowedOrigins("*")` blijven bewust ongewijzigd; de interceptor is de
  grens.
- `RequestWebSocketHandler.broadcast` is nu `broadcast(username, payload)` en
  slaat sessies van andere gebruikers over. Het opruimen van dode sessies
  bleef in dezelfde vorm staan. `RequestServiceImpl.create` en `.upsert` zijn
  de enige twee callers en geven hun `username` mee; er is geen codepad meer
  dat naar alle sessies stuurt.
- **Afwijking t.o.v. de storytekst (bewust):** de interceptor gebruikt niet
  rechtstreeks `JwtService`, maar de nieuwe `AuthService.validateToken(token)`
  die daarnaar delegeert. `JwtService` zit in `auth.infrastructure` en is dus
  niet-geëxposeerd; `ModuleStructureTest` (lege allowlist) faalde op vier
  schendingen `Module 'websocket' depends on non-exposed type … JwtService`.
  Validatie via de publieke module-API van `auth` lost dat op zonder de
  allowlist te verruimen, en komt op exact dezelfde `JwtService.validate` uit.

**Frontend**

- `lib/api/ws_client.dart`: nieuwe top-level `requestsWsUrl(token)` bouwt de
  URL met `?token=<jwt>`; `connect(token)` onthoudt het token zodat óók elke
  reconnect in `_open()` het meestuurt. Zonder token wordt er niet verbonden.
- `lib/providers/data_providers.dart`: geeft `_api.token` mee bij connect. De
  comment over "the broadcast carries updates for ALL users" is vervangen: een
  onbekend id is geen andermans verzoek meer, en `_reloadFromServer()` blijft
  als vangnet (niet meer als privacymaatregel).
- `lib/providers/auth_provider.dart`: `AuthNotifier` krijgt een optionele
  `Ref` en invalideert bij `logout()` `requestProvider`. Die provider is niet
  autoDispose, dus zonder deze stap bleef de socket van gebruiker A luisteren
  terwijl B inlogde. De optionele parameter houdt de bestaande
  `_FakeAuthNotifier(super.api, …)`-tests intact.

**Tests (zelf geschreven)**

- `websocket/JwtHandshakeInterceptorTest.kt` (6): geldig token laat door en
  zet de gebruikersnaam; ontbrekend, leeg, onzin-, vreemd-secret- en verlopen
  token geven `401` en zetten geen identiteit.
- `websocket/RequestWebSocketHandlerTest.kt` (6): levering alleen aan de
  sessies van de eigenaar, `serverVersion` alleen naar de verbindende sessie,
  en het opruimen van gesloten/kapotte sessies zonder de rest te raken.
- `e2e/WsTestClient.connect(port, mapper, token)` zet het token op de
  handshake-URL; `RequestWebSocketE2eTest` verbindt overal met een geldig
  token. De oude test "elke verbonden client ontvangt de statusberichten van
  elk verzoek" is vervangen door "statusberichten gaan alleen naar de eigenaar
  en niet naar een andere gebruiker" (A ziet `PENDING → PROCESSING → DONE`, B
  ziet binnen een ruime marge niets met dat id), plus een nieuwe test die een
  handshake zonder token en met een onzin-token beide op `401` vastlegt.
- `frontend/test/ws_client_test.dart` (5) en
  `frontend/test/auth_logout_ws_test.dart` (2).

## Verificatie

- `mvn -B --no-transfer-progress clean verify`: exit 0 — 128 unit-tests
  (was 116) + 77 e2e-tests (was 76), 0 failures/errors, ~4 min.
  `target/jacoco.exec` (372 KB) en `target/jacoco-it.exec` (9,2 MB) zijn
  geschreven; `grep -icE 'warning|deprecat|self-attach'` geeft 1 hit, de
  bekende Nederlandse `[Podcast]`-logregel.
  Anders dan de story aannam wás Docker hier beschikbaar (`/var/run/docker.sock`),
  dus de e2e-criteria zijn lokaal echt gedraaid.
- `flutter test` in `frontend/`: 36 tests groen (was 29). `flutter analyze`:
  6 pre-existing infos (was 7 — de melding op `ws_client.dart:20` verviel met
  de herschreven URL-opbouw). `frontend/pubspec.lock` is niet gemuteerd.

## Buiten scope gelaten

De documentatie in `docs/factory/` en `specs/` (o.a. `technical-spec.md`,
`backend-functional-spec.md`, `frontend-spec.md`, `openapi.yaml`) beschrijft
`/ws/requests` nog als onbeschermd/ongefilterd. Dat is expliciet belegd bij de
documentation-subtaak SF-2169.
