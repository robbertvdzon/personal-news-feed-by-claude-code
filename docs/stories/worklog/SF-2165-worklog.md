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
