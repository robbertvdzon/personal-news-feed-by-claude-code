# SF-2165 - [Audit] Authenticeer de WebSocket /ws/requests en stuur statusupdates alleen naar de eigenaar

## Story

[Audit] Authenticeer de WebSocket /ws/requests en stuur statusupdates alleen naar de eigenaar

<!-- refined-by-factory -->

## Scope

De WebSocket `/ws/requests` wordt geauthenticeerd en per gebruiker gefilterd, zodat het volledige `NewsRequest`-domeinmodel (met `subject`, `extraInstructions`, `sourceItemTitle`, `categoryResults`) alleen nog naar de eigenaar gaat.

**Backend (`newsfeedbackend/newsfeedbackend/src/main`)**

1. Nieuwe `JwtHandshakeInterceptor` naast `websocket/RequestWebSocketHandler.kt`: implementeert `HandshakeInterceptor`, leest het token uit queryparameter `token`, valideert via de bestaande `JwtService.validate(...)` (geeft `Pair<username, role>?`) en zet de gebruikersnaam in `attributes["username"]`. Ontbrekend of ongeldig token → `beforeHandshake` geeft `false` terug en zet expliciet responsestatus `401`; er wordt geen sessie geregistreerd. Precedent voor `?token=`: `auth/infrastructure/JwtAuthFilter.kt:37-41` (audio-endpoint).
2. `WebSocketConfig` (`RequestWebSocketHandler.kt:59-65`): `.addInterceptors(...)` toevoegen achter `registry.addHandler(handler, "/ws/requests")`.
3. `RequestWebSocketHandler.broadcast` wordt `broadcast(username: String, payload: Any)` en stuurt alleen naar sessies waarvan `session.attributes["username"] == username`. Het bestaande opruimen van dode sessies (`:47-55`) blijft ongewijzigd van vorm.
4. De twee callers worden `ws.broadcast(username, saved)`: `request/domain/RequestServiceImpl.kt:57` (`create`) en `:97` (`upsert`). Dat zijn de enige twee `broadcast(`-aanroepen in `src/main`.
5. `SecurityConfig.kt:35` blijft ongewijzigd (`/ws/**` blijft `permitAll`); de handshake-interceptor is de grens. `setAllowedOrigins("*")` blijft eveneens buiten scope.

**Frontend (`frontend/`)**

6. `lib/api/ws_client.dart`: `connect()` krijgt het JWT mee en hangt `?token=<jwt>` aan de WS-URL; ook de reconnect in `_open()` gebruikt het huidige token. Zonder token wordt er niet verbonden.
7. `lib/providers/data_providers.dart:259` geeft het token mee (`ref.read(apiProvider).token`). De comment op `:277-284` ("the broadcast carries updates for ALL users") wordt bijgewerkt naar de nieuwe werkelijkheid; de `_reloadFromServer()`-tak bij een onbekend id blijft bestaan als vangnet.
8. Na uitloggen wordt de WebSocket gesloten en bij een volgende login opnieuw opgezet met het token van de dán ingelogde gebruiker (bijvoorbeeld door `requestProvider` te invalideren in `AuthNotifier.logout`). Vandaag blijft de socket open omdat `requestProvider` niet autoDispose is en bij logout niet ge-invalidate wordt — zonder deze stap ontvangt het toestel na een gebruikerswissel nog steeds de berichten van de vorige gebruiker. `specs/frontend-spec.md:210` belooft dit gedrag al.
9. `frontend-reader` gebruikt de WebSocket niet en blijft ongemoeid.

**Tests (`src/test/kotlin/.../e2e/`)**

10. `WsTestClient.connect(...)` krijgt een optionele/verplichte `token`-parameter en zet die als queryparameter op de handshake-URL.
11. De vier blijvende tests in `RequestWebSocketE2eTest.kt` (`:115`, `:130`, `:142`, `:196`) verbinden voortaan met een geldig token uit `registerUser(...)` (`E2eTestBase.TestUser.token`). De twee tests die nu vóór `registerUser` verbinden (`:115`, `:130`) registreren eerst een gebruiker.
12. `RequestWebSocketE2eTest.kt:174-193` (`elke verbonden client ontvangt de statusberichten van elk verzoek`) wordt vervangen door een test met twee verschillende gebruikers: A's verzoek levert bij B géén bericht op met dat id, terwijl A de reeks wél ziet. De motiverende comment op `:181-185` verdwijnt/wordt omgekeerd.
13. Nieuwe test: een handshake zonder token en een handshake met een onzin-token worden beide geweigerd.

**Documentatie**

14. `docs/factory/technical-spec.md:96`: de expliciet benoemde uitzondering op de "permitAll geeft nooit een domeinmodel terug"-regel vervalt; beschrijf in plaats daarvan dat `/ws/requests` op de handshake authenticeert en per eigenaar filtert.
15. `docs/factory/technical-spec.md:95`: de slotzin "het id zelf is geen geheim (`/ws/requests` staat op `permitAll` en broadcast alle statusupdates)" klopt niet meer.
16. `specs/backend-technical-spec.md:436` ("beide verbonden clients krijgen dezelfde statusberichten, want de server filtert niet per gebruiker") en `:440` ("`/ws/**` is `permitAll` dus er is geen token nodig") — let op: de storyverwijzing naar :402-403/:408 is verschoven, de juiste regels zijn 436 en 440.
17. `specs/backend-functional-spec.md:132-136` (§5 WebSocket): "Geen authenticatie vereist" en de "Multi-user broadcast"-bullet vervangen door authenticatie via `?token=` en per-eigenaar-levering. Ook de zin over `NewsRequest`-updates naar "alle verbonden clients" (~`:140`) meenemen.
18. `specs/frontend-spec.md:198-210`: het "de frontend moet zelf filteren"-protocol beschrijven als vangnet in plaats van als noodzaak, en het meesturen van het token bij connect documenteren.
19. **Extra t.o.v. de oorspronkelijke storytekst:** `specs/openapi.yaml:9-17` (`info.description`, WebSocket-blok) zegt letterlijk "Geen authenticatie vereist" en moet mee. De opsomming van permitAll-paden op `:7` blijft feitelijk juist (`SecurityConfig` verandert niet), maar krijgt de nuance dat `/ws/**` zijn eigen handshake-authenticatie heeft.

## Acceptance criteria

1. Een WebSocket-handshake op `/ws/requests` zonder `token`-queryparameter wordt geweigerd (geen `101`-upgrade); idem met een ongeldig/verlopen token. Beide gevallen zijn met een e2e-test vastgelegd.
2. Een handshake mét een geldig token slaagt en de verbindende client krijgt direct precies één `serverVersion`-bericht (`sha`/`buildTime` = `"unknown"` in de testomgeving), en dat bericht wordt niet naar andere verbonden clients gestuurd — het bestaande gedrag uit `RequestWebSocketE2eTest` blijft ongewijzigd.
3. Statusberichten van een verzoek van gebruiker A komen wél binnen bij een verbinding van A (reeks `PENDING → PROCESSING → DONE` met het volledige `NewsRequest`-object, zonder `type`-veld) en niet bij een gelijktijdig verbonden gebruiker B. Een e2e-test met twee gebruikers legt dit vast; de oude test die het tegenovergestelde vastlegde bestaat niet meer.
4. `grep -rn "broadcast(" src/main` toont uitsluitend de nieuwe signatuur met `username` en de twee aangepaste callers; er is geen codepad meer dat naar alle sessies stuurt.
5. Sluiten van de ene verbinding blokkeert de levering aan de andere verbinding van dezelfde gebruiker niet (bestaande test `:196` blijft geldig, met token).
6. De Flutter-app stuurt het JWT mee bij connect én bij elke reconnect, en verbindt niet zonder token. Na uitloggen en opnieuw inloggen (ook als een andere gebruiker) is de actieve verbinding opgezet met het token van de nu ingelogde gebruiker.
7. Statusupdates komen in de app nog steeds live binnen in de verzoekenlijst; `serverVersion` blijft `versionProvider` voeden.
8. `mvn -B --no-transfer-progress clean test` blijft groen (116 tests) en `mvn -B --no-transfer-progress clean verify` (de e2e-suite in CI, `.factory/verification.yaml` → `backend-maven-verify`) is groen. `flutter test` in `frontend/` blijft groen.
9. Geen enkel document beschrijft `/ws/requests` nog als onbeschermd of als ongefilterde broadcast: `grep -rn "filtert niet per gebruiker\|geen token nodig\|Geen authenticatie vereist"` levert in `specs/` en `docs/factory/` geen treffer meer op die over de WebSocket gaat, en de vijf genoemde documentatieplekken (incl. `specs/openapi.yaml`) zijn bijgewerkt.

## Aannames

- **Weigeringsvorm:** de interceptor zet responsestatus `401` en geeft `false` terug. Vanuit de JDK-`HttpClient`-testclient uit zich dat als een falende `buildAsync(...)`-future (`WebSocketHandshakeException`); de e2e-test asserteert op die uitzondering en niet op een specifieke close-code, omdat er geen WebSocket-sessie tot stand komt.
- **Token in de query-string** is geaccepteerd (browser-WebSocket kan geen `Authorization`-header zetten); dit volgt het bestaande precedent van het audio-endpoint. Het risico van tokens in access-logs wordt hiermee bewust gedeeld met dat endpoint en niet in deze story aangepakt.
- **`setAllowedOrigins("*")` blijft staan.** De authenticatie is de grens; een origin-restrictie is een aparte afweging (de app draait ook als mobiele client zonder browser-origin).
- **Sessie-identiteit** wordt uitsluitend uit `session.attributes["username"]` gelezen, gezet op handshake-moment. Een token dat later verloopt beëindigt een lopende verbinding niet — dat is buiten scope.
- **Reconnect-gedrag frontend:** een geweigerde handshake leidt tot de bestaande 5-seconden-reconnectlus. Dat blijft zo (gelijk aan het huidige gedrag bij een onbereikbare backend); er komt geen backoff of foutmelding in de UI bij.
- **De `_reloadFromServer()`-tak bij een onbekend id blijft bestaan** als vangnet voor berichten die tijdens een herlaad binnenkomen; hij is na deze story alleen geen privacymaatregel meer.
- **Geen DTO-laag.** Deze story lost het lek op via autorisatie, niet door een `NewsRequestDto` te introduceren; het volledige domeinmodel blijft over de lijn gaan, nu alleen naar de eigenaar. De gebruikersnamen in de vaste id's `hourly-update-<username>` / `daily-summary-<username>` (`RequestServiceImpl.kt:109-110`) blijven daarmee ongewijzigd — na deze story zien alleen nog de eigen sessies ze.
- **Verificatiebeperking van de ontwikkelomgeving:** in de agent-container is `docker` niet beschikbaar, dus de e2e/failsafe-suite (Testcontainers) is lokaal niet te draaien; alleen `mvn clean test` (surefire, excludeert `**/e2e/**`) en `flutter test` zijn lokaal aantoonbaar. De e2e-acceptatiecriteria worden door CI bevestigd.

## Eindsamenvatting

## Eindsamenvatting SF-2165 — WebSocket `/ws/requests` authenticeren en per eigenaar leveren

**Probleem**
De WebSocket `/ws/requests` stond op `permitAll` en stuurde élk `NewsRequest` (inclusief `subject`, `extraInstructions`, `sourceItemTitle` en `categoryResults`) naar álle verbonden clients. Iedereen die de socket opende, kon dus de verzoeken van andere gebruikers meelezen.

**Wat is gebouwd**

*Backend*
- Nieuwe `websocket/JwtHandshakeInterceptor.kt`: leest het JWT uit queryparameter `token` (browser-WebSockets kunnen geen `Authorization`-header sturen — zelfde precedent als het audio-endpoint), valideert het en zet de gebruikersnaam in `session.attributes["username"]`. Ontbrekend/ongeldig/verlopen token → HTTP `401`, handshake geweigerd, geen sessie en geen `serverVersion`-bericht.
- Geregistreerd in `WebSocketConfig`. `SecurityConfig` (`/ws/**` blijft `permitAll`) en `setAllowedOrigins("*")` zijn bewust ongewijzigd: de interceptor is de grens.
- `RequestWebSocketHandler.broadcast` is nu `broadcast(username, payload)` en levert alleen aan sessies van de eigenaar; de twee callers (`RequestServiceImpl.create` en `.upsert`) geven hun username mee. Er is geen codepad meer dat naar alle sessies stuurt.

*Frontend (Flutter)*
- `ws_client.dart` bouwt de URL met `?token=<jwt>` en onthoudt het token, zodat ook elke reconnect het meestuurt; zonder token wordt niet verbonden.
- `RequestNotifier.build()` is token-reactief (`ref.watch(authProvider.select((s) => s.token))`): bij login, logout en gebruikerswissel wordt de oude socket gesloten en een nieuwe opgezet met het token van de nu ingelogde gebruiker.

**Gemaakte keuzes (afwijkend van de oorspronkelijke story)**
- De interceptor valideert via de publieke module-API `AuthService.validateToken(...)` in plaats van rechtstreeks `JwtService`; dat laatste brak `ModuleStructureTest` (niet-geëxposeerd type). Functioneel identiek.
- De geplande `ref.invalidate(requestProvider)` bij logout is vervangen door token-reactiviteit. De invalidate bleek een echte bug op te leveren (na uitloggen kwam er geen verbinding meer tot stand) en is met de nieuwe afhankelijkheid ook technisch onmogelijk (`CircularDependencyError`). Deze blocker is in de review gevonden en in ronde 2 opgelost.
- Token in de query-string is bewust geaccepteerd (bestaand precedent); tokens-in-access-logs blijft een bekend, gedeeld risico buiten deze story.

**Getest**
- Backend: `mvn clean verify` groen — 129 unit-tests (was 116) + 77 e2e (was 76), 0 failures. Nieuwe unit-tests voor de interceptor (6) en de per-eigenaar-levering/sessie-opruiming (7); e2e-tests voor handshake mét/zonder/met onzin-token en een twee-gebruikers-test die vastlegt dat A's statusreeks niet bij B aankomt. De oude test die het tegenovergestelde vastlegde is verwijderd.
- Frontend: `flutter test` 37 groen (was 29), incl. regressietest op login A → logout → login B; `flutter analyze` alleen pre-existing meldingen.
- Live op preview `pnf-pr-229`: 401 bij handshake zonder/met ongeldig token, correcte levering aan A en 0 berichten bij B, live statusupdates in de UI, socket sluit bij logout en opent opnieuw met het nieuwe token na login als B.

**Bewust niet gedaan**
- Documentatie in `docs/factory/` en `specs/` (waaronder `openapi.yaml`) beschrijft de WebSocket nog als onbeschermd — belegd bij subtaak SF-2169.
- Geen DTO-laag: het volledige domeinmodel gaat nog over de lijn, nu alleen naar de eigenaar. Ook geen origin-restrictie en geen afbreken van lopende verbindingen bij tokenverloop.
- **Losse bevinding (pre-existing, niet door deze story veroorzaakt):** de web-deployment draait met een lege `API_BASE_URL`, waardoor de WS-URL relatief wordt en de web-app helemaal geen WebSocket opent — ook op `main` al zo. Verdient een eigen issue.

```json
```
