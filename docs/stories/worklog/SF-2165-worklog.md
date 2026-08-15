# SF-2165 - Worklog

Story-context bij eerste pickup:
WebSocket /ws/requests authenticeren en per eigenaar leveren

Voer de hele wijziging in één samenhangende stap uit; zie het implementatieplan in docs/stories/worklog/SF-2165-worklog.md.

Backend (newsfeedbackend/newsfeedbackend/src/main): nieuwe HandshakeInterceptor naast websocket/RequestWebSocketHandler.kt die het token uit queryparameter 'token' leest, valideert via de bestaande JwtService.validate(...) (geeft Pair<username,role>?) en de gebruikersnaam in attributes["username"] zet; ontbrekend/ongeldig token -> responsestatus 401 en false teruggeven, zodat er geen sessie ontstaat en er ook geen serverVersion-bericht volgt. Registreer de interceptor in WebSocketConfig achter registry.addHandler(handler, "/ws/requests"). SecurityConfig (/ws/** blijft permitAll) en setAllowedOrigins("*") blijven ongewijzigd - de interceptor is de grens. Wijzig broadcast naar broadcast(username, payload) en lever alleen aan sessies waarvan session.attributes["username"] gelijk is; behoud het opruimen van dode sessies in de huidige vorm. Pas de twee callers aan: RequestServiceImpl.create en .upsert (beide hebben username in scope). Na afloop bestaat er geen codepad meer dat naar alle sessies stuurt.

Frontend (frontend/): lib/api/ws_client.dart krijgt het JWT mee bij connect() en hangt ?token=<jwt> aan de URL, ook bij elke reconnect in _open(); zonder token wordt niet verbonden. lib/providers/data_providers.dart geeft het token mee bij connect en de comment over 'the broadcast carries updates for ALL users' wordt bijgewerkt (het _reloadFromServer()-vangnet blijft, maar is geen privacymaatregel meer). Zorg dat de socket bij uitloggen wordt gesloten en bij een volgende login opnieuw wordt opgezet met het token van de dan ingelogde gebruiker (bijv. ref.invalidate(requestProvider) in AuthNotifier.logout) - requestProvider is vandaag niet autoDispose en wordt niet ge-invalideerd, dus zonder deze stap blijft de socket van gebruiker A luisteren terwijl B inlogt. frontend-reader blijft ongemoeid.

Tests (src/test/kotlin/.../e2e/, hoort bij dit ontwikkelwerk): WsTestClient.connect(...) krijgt een token-parameter die als queryparameter op de handshake-URL komt. De vier blijvende tests in RequestWebSocketE2eTest verbinden met een geldig token uit registerUser(...) (E2eTestBase.TestUser.token); de twee die vandaag voor registerUser verbinden, registreren eerst. Draai de test 'elke verbonden client ontvangt de statusberichten van elk verzoek' om naar twee gebruikers: A ziet de reeks PENDING/PROCESSING/DONE, B ontvangt geen bericht met dat id (asserteer op afwezigheid binnen een ruime timeout; B's eigen serverVersion-bericht is een bruikbaar anker). Verwijder/keer de motiverende comment om. Voeg een test toe die zowel een handshake zonder token als met een onzin-token weigert (de JDK-testclient ziet dat als een falende future / WebSocketHandshakeException, niet als een close-code).

Verificatie: draai lokaal 'mvn -B --no-transfer-progress clean test' (116 tests, sluit **/e2e/** uit) en 'flutter test' in frontend/. Docker is niet beschikbaar in de container, dus de e2e/failsafe-suite is lokaal niet te draaien - meld dat expliciet in plaats van het als getest te presenteren; CI (backend-maven-verify) bevestigt de e2e-criteria. Voer zelf de reviewstap uit voor je oplevert. Documentatiewijzigingen in docs/factory/ en specs/ zijn voor de documentation-subtaak.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-2166 (developer): handshake-authenticatie via JwtHandshakeInterceptor,
  broadcast(username, payload) met per-eigenaar-levering, token mee vanuit de
  Flutter-app (connect + reconnect) en requestProvider-invalidatie bij logout.
  Bewuste afwijking: de interceptor valideert via de publieke module-API
  AuthService.validateToken i.p.v. rechtstreeks JwtService, omdat
  ModuleStructureTest (lege allowlist) rood ging op 'websocket depends on
  non-exposed type JwtService'. Zie docs/stories/SF-2166-websocket-authenticeren-per-eigenaar.md.
- Vangnet groen: mvn -B --no-transfer-progress clean verify exit 0 (128 unit +
  77 e2e), flutter test in frontend/ 36 groen. Docker WAS beschikbaar in de
  container, dus de e2e-suite is lokaal echt gedraaid.

Review SF-2166 (ronde 1) — afgekeurd, 1 blocker:
- [blocker] Na uitloggen + opnieuw inloggen komt er in de app geen WebSocket
  meer tot stand (AC6/AC7). `AuthNotifier.logout()` invalideert
  `requestProvider` terwijl het instellingenscherm hem nog watcht
  (`_BackgroundJobsSection`, settings_screen.dart:342/359). Riverpod bouwt de
  provider daardoor meteen opnieuw op, met `_api.token == null` (setToken(null)
  staat vóór de invalidate), zodat `connect(null)` geen verbinding opent. De
  provider is niet autoDispose en wordt bij login niet ge-invalideerd, dus de
  volgende `read` hergebruikt die dode notifier. Reviewer reproduceerde dit met
  een tijdelijke widgettest (verwijderd): opgenomen tokens per build =
  [token-a, null] en na login van B nog steeds [token-a, null].
  Richting: invalideer `requestProvider` ook na een geslaagde login/bootstrap,
  of maak de verbinding token-reactief (`ref.watch(authProvider.select((s) =>
  s.token))` in `RequestNotifier.build`).
- [suggestie] `broadcast` ruimt dode sessies alleen nog op binnen de sessies van
  de eigenaar (de `return@forEach` staat vóór de `isOpen`-check). Sessies van
  andere gebruikers die zonder `afterConnectionClosed` sneuvelen blijven in
  `sessions` staan tot die gebruiker zelf een bericht krijgt.
- Backend zelf is akkoord: AC4 klopt (`grep -rn "broadcast(" src/main` = 1
  declaratie + 2 callers met username), modulith-grens via
  `AuthService.validateToken` is correct, e2e-weigeringstest en
  twee-gebruikerstest dekken AC1/AC3. Harnessbewijs `backend-maven-verify`
  passed op tree 59378b28…, gelijk aan `git rev-parse HEAD^{tree}`.

Ronde 2 (developer) — reviewbevindingen verwerkt:
- [blocker AC6/AC7] `RequestNotifier.build()` is token-reactief gemaakt:
  `ref.watch(authProvider.select((s) => s.token))` bepaalt nu met welk token
  de socket wordt opgezet, en elke build maakt een verse `RequestsWebSocket`
  (de oude sluit via `onDispose`). De `ref.invalidate(requestProvider)` in
  `AuthNotifier.logout()` is daarmee overbodig én onmogelijk geworden:
  requestProvider hangt nu van authProvider af, dus Riverpod gooit
  `CircularDependencyError` op zo'n invalidate (in de test aangetoond). De
  optionele `Ref`-parameter op `AuthNotifier` is weer verwijderd.
  Nieuwe regressietest `frontend/test/auth_logout_ws_test.dart` draait op de
  echte notifier met een `container.listen` (de eager-rebuild-situatie uit de
  review): login A → `token-van-a`, logout → `null`, login B → `token-van-b`.
  Tegenproef gedaan: met `ref.read` i.p.v. `ref.watch` faalt de test.
- [suggestie] `RequestWebSocketHandler.broadcast` controleert `isOpen` nu vóór
  het eigenaarsfilter, zodat dode sessies van álle gebruikers worden
  opgeruimd; extra unit-test legt dat vast.
- Vangnet ronde 2: `mvn -B --no-transfer-progress clean verify` exit 0 —
  129 unit (was 128) + 77 e2e, 0 failures/errors, 4:12 min; jacoco.exec
  372 KB + jacoco-it.exec 9,2 MB; `grep -icE 'warning|deprecat|self-attach'`
  = 1 (bekende `[Podcast]`-logregel). `flutter test` 37 groen (was 36),
  `flutter analyze` 6 pre-existing infos, `pubspec.lock` onaangeroerd.
