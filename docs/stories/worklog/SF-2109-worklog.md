# SF-2109 - Worklog

Story-context bij eerste pickup:
E2e-test voor WebSocket /ws/requests toevoegen

Voeg `RequestWebSocketE2eTest.kt` toe in `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/`, ervend van `E2eTestBase`, plus één kleine hulpklasse in dezelfde testmap die een `java.net.http.WebSocket.Listener` verpakt (fragmenten accumuleren tot `last == true`, zelf `webSocket.request(1)` aanroepen, geparsede `JsonNode`s in een `LinkedBlockingQueue`, `poll(timeout)` geeft `null` bij time-out). Verbind via `HttpClient.newWebSocketBuilder()` met `ws://localhost:$port/ws/requests` (geen token nodig, `/ws/**` is permitAll). Geen nieuwe dependencies. Voeg een eigen `@DynamicPropertySource` toe die `app.tavily.api-key` op een dummy zet (zonder key doet TavilyClient geen HTTP-call; patroon `RequestsE2eTest`).

Leg AC1-6 uit de story vast: (1) na connect precies één bericht met `type = "serverVersion"` en `sha`/`buildTime` beide `"unknown"`; (2) een tweede verbinding levert bij de eerste géén tweede `serverVersion` (korte-time-out-poll geeft `null`); (3) met openstaande verbinding een ad-hoc verzoek via `POST /api/requests` (`serveTavily(...)` + gescript `ACTION_ADHOC_SUMMARIZE`), berichten verzamelen tot `DONE`, assert dat ná `serverVersion` geen bericht een `type`-veld heeft en dat de berichten met het `id` van dit verzoek op `DONE` eindigen; (4) in minstens één bericht zijn `id`, `status`, `subject` en `newItemCount` aanwezig; (5) met twee open verbindingen ontvangen beide de statusberichten, met commentaarregel + verwijzing naar `frontend-spec.md:199-206` waarom dat bewust is; (6) sluit met twee open verbindingen de eerste netjes en assert dat de tweede een daarna uitgelokte statuswijziging nog ontvangt.

Synchronisatie: verbind altijd vóór het uitlokken van de statuswijziging en wacht eerst het `serverVersion`-bericht af als anker; geen vaste `sleep`, maar `poll(timeout)` met royale time-out en de bestaande `await{}`-helper (de enige korte time-out is de negatieve assertie bij punt 2). Elke test registreert zijn eigen gebruiker via `registerUser(...)`; sluit alle verbindingen in een `@AfterEach` (de handler is een singleton binnen de fork). Assert per-`id`, want de hardcoded `RssScheduler`-cron kan bij een run over het hele uur een `hourly-update-*`-broadcast injecteren.

Wijzig geen productiecode: de diff bevat uitsluitend bestanden onder `src/test/` (plus het worklog). Legt de test een echt verschil met de specificatie bloot, meld dat dan expliciet in het worklog als stof voor een aparte story - niet stilzwijgend repareren en niet wegasserteren. Draai zelf `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend` tweemaal achter elkaar en zet de uitkomst van beide runs in het worklog. Sluit af met een eigen review-stap over de diff (assertiesterkte, geen afgezwakte checks, geen productiecode geraakt).

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `WsTestClient.kt` toegevoegd (test-only hulpklasse): JDK-`WebSocket.Listener` die fragmenten
  accumuleert tot `last == true`, zelf `webSocket.request(1)` aanroept, geparsede `JsonNode`s in een
  `LinkedBlockingQueue` zet en een `poll(timeout)` biedt die `null` geeft bij time-out. `connect(port,
  mapper)` bouwt de verbinding via `HttpClient.newWebSocketBuilder()`; `close()` doet een normal
  closure en is veilig bij herhaald aanroepen (nodig omdat AC6 zelf al sluit én de `@AfterEach` sluit).
- `RequestWebSocketE2eTest.kt` toegevoegd met 5 tests die AC1-6 dekken:
  1. `direct na connect komt precies een serverVersion-bericht` - AC1 (+ `sha`/`buildTime` = `"unknown"`,
     de gedocumenteerde fallback) en meteen het "precies een"-deel via een korte stilte-poll.
  2. `serverVersion gaat alleen naar de verbindende client en wordt niet gebroadcast` - AC2.
  3. `statuswijzigingen van een verzoek komen als volledige NewsRequest-objecten binnen` - AC3 + AC4.
  4. `elke verbonden client ontvangt de statusberichten van elk verzoek` - AC5, met commentaarregel en
     verwijzing naar `specs/frontend-spec.md:199-206` waarom het niet-filteren bewust is.
  5. `een gesloten verbinding blokkeert de broadcast naar de overige clients niet` - AC6.
- Synchronisatie zoals afgesproken: eerst verbinden en het `serverVersion`-bericht consumeren als
  anker dat de sessie server-side geregistreerd is, pas daarna de `POST /api/requests`. Geen enkele
  vaste `sleep`; de enige korte time-out (2 s) zit in de twee negatieve asserties (AC1 "precies een"
  en AC2). Berichten worden per `id` gefilterd, zodat een `hourly-update-*`-broadcast van de
  hardcoded `RssScheduler`-cron de asserties niet kan omgooien; de assertie "na `serverVersion` heeft
  geen enkel bericht een `type`-veld" geldt wel over alle ontvangen berichten en blijft ook dan correct.
- Eigen `@DynamicPropertySource` met dummy `app.tavily.api-key` (patroon `RequestsE2eTest`), zodat de
  Tavily-calls echt bij de `FakeContentServer` uitkomen. Geen nieuwe dependencies.
- Extra assertie bovenop het minimum van AC3: de statusreeks van het verzoek is exact
  `PENDING -> PROCESSING -> DONE` (distinct), niet alleen "eindigt op DONE" - dat bleek stabiel over
  beide volledige runs plus een losse klasse-run.

Spec-vs-code-bevindingen:
- Geen. Alle zes de criteria gedragen zich zoals `specs/backend-functional-spec.md` §5 (WebSocket) en
  `specs/frontend-spec.md` (§ WebSocket-integratie) beschrijven; er is niets weggeasserteerd en er is
  geen drift die om een aparte story vraagt.
- Wel expliciet vermeld (was al een aanname in de story, geen afwijking): bij een nette close haalt
  Spring de sessie al in `afterConnectionClosed` uit de lijst, dus de dead-session-tak in
  `RequestWebSocketHandler.broadcast` wordt door AC6 niet gegarandeerd doorlopen. AC6 is daarom op
  waarneembaar gedrag geformuleerd (de overige client blijft berichten krijgen), niet op codedekking.

Testresultaten (`mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`):
- Run 1: BUILD SUCCESS, exitcode 0 - 116 unit-tests + 71 e2e-tests, 0 failures, 0 errors, ~3:42 min.
- Run 2: BUILD SUCCESS, exitcode 0 - 116 unit-tests + 71 e2e-tests, 0 failures, 0 errors, ~3:35 min.
- Baseline was 66 e2e-tests; de 5 nieuwe zijn de enige toename. 0 `[WARNING]`-regels in de
  Maven-output; de warning-vrije baseline uit SF-1945 houdt stand.

Eigen review over de diff:
- Alleen twee nieuwe bestanden, beide onder `src/test/kotlin/.../e2e/` (plus dit worklog). Geen
  productiecode, geen `pom.xml`, geen specs, geen lockfiles aangeraakt.
- Asserties zijn positief en concreet (exacte waarden voor `type`/`sha`/`buildTime`/`subject`/
  `newItemCount`/statusreeks); geen `assertTrue(true)`-achtige of louter niet-null-checks. De twee
  `assertNull`-checks zijn bewuste negatieve asserties met een korte time-out en een boodschap.
- Geen vaste sleeps, geen afgezwakte time-outs: wachten gebeurt via `poll(timeout)`; het uitblijven
  van een verwacht bericht laat de test falen met de tot dan toe ontvangen berichten in de melding.
