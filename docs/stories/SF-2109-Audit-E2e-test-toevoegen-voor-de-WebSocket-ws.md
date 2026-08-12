# SF-2109 - [Audit] E2e-test toevoegen voor de WebSocket /ws/requests

## Story

[Audit] E2e-test toevoegen voor de WebSocket /ws/requests

<!-- refined-by-factory -->

## Scope

Voeg één nieuwe e2e-testklasse toe die het WebSocket-endpoint `/ws/requests` vastlegt zoals `specs/backend-functional-spec.md` (§ WebSocket, r130-140) en `specs/frontend-spec.md` (r199-206) het beloven.

In scope:
- `RequestWebSocketE2eTest.kt` in `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/`, ervend van `E2eTestBase`.
- Eén kleine hulpklasse in dezelfde testmap die een `java.net.http.WebSocket.Listener` verpakt: binnengekomen tekstberichten worden geparsed en in een `LinkedBlockingQueue<JsonNode>` gezet, met een `poll(timeout)`-methode die `null` teruggeeft bij time-out.

Expliciet buiten scope:
- Elke wijziging in productiecode (`RequestWebSocketHandler.kt`, `RequestServiceImpl.kt`, `SecurityConfig.kt`, specs). Legt de test een echt verschil met de specificatie bloot, dan wordt dat in het worklog gemeld als stof voor een aparte story — niet stilzwijgend gerepareerd en niet weggeasserteerd.
- Nieuwe dependencies. `java.net.http.HttpClient.newWebSocketBuilder()` zit in de JDK; `spring-boot-starter-websocket` en `awaitility` staan al in `pom.xml`.
- De Flutter-kant: reconnect-logica in `ws_client.dart`, tests voor `version_provider.dart` en `RequestNotifier._apply` (die vragen eerst een injectiepunt voor `RequestsWebSocket`).
- Opstartlogica en ShedLock.

## Acceptance criteria

1. **serverVersion bij connect.** Na het openen van een verbinding komt binnen enkele seconden precies één bericht met `type = "serverVersion"`, met de velden `sha` en `buildTime`. In de testomgeving zijn `BUILD_SHA`/`BUILD_TIME` niet gezet, dus beide zijn gelijk aan de gedocumenteerde fallback `"unknown"`.
2. **serverVersion is geen broadcast.** Wordt na verbinding A een tweede verbinding B geopend, dan ontvangt A géén tweede `serverVersion`-bericht (poll met korte time-out geeft `null`). Dit legt de spec-zin "alleen naar de verbindende client, geen broadcast" vast.
3. **NewsRequest-broadcast bij statuswijziging.** Met een openstaande verbinding wordt via `POST /api/requests` een ad-hoc verzoek aangemaakt (patroon `RequestsE2eTest`: `serveTavily(...)` plus gescript `ACTION_ADHOC_SUMMARIZE`-antwoord). De binnenkomende berichten worden verzameld tot het verzoek `DONE` is. Geasserteerd wordt dat: (a) ná het `serverVersion`-bericht geen enkel bericht een `type`-veld heeft, (b) de berichten van dit verzoek het `id` van het aangemaakte verzoek dragen, en (c) de reeks statussen eindigt op `DONE`.
4. **Het volledige object gaat over de lijn.** In minstens één ontvangen `NewsRequest`-bericht zijn `id`, `status`, `subject` en `newItemCount` aanwezig, zodat `NewsRequest.fromJson` aan de clientkant er echt op kan bouwen. Dit is hier extra van belang omdat `NewsRequest` zonder DTO-laag wordt geserialiseerd: de REST-kant is via de e2e-suite gedekt, deze kant niet.
5. **De broadcast gaat naar alle clients.** Met twee open verbindingen en één verzoek aangemaakt door gebruiker A ontvangen **beide** verbindingen de statusberichten. Dit is bewust gedrag (de frontend filtert zelf, `frontend-spec.md:204-206`); dat staat als commentaarregel met spec-verwijzing in de test, zodat een toekomstige keuze om wél per gebruiker te filteren een expliciete testwijziging afdwingt in plaats van een stille gedragswijziging.
6. **Een verbroken verbinding blokkeert de rest niet.** Met twee open verbindingen wordt de eerste netjes gesloten; een daarna uitgelokte statuswijziging komt nog gewoon aan op de tweede verbinding.
7. **Geen productiecode gewijzigd** — de diff bevat uitsluitend bestanden onder `src/test/`.
8. **Niet flaky:** `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend` slaagt twee keer achter elkaar. De uitkomst van beide runs staat in het worklog.

## Aannames

- **Volgorde en synchronisatie.** Verbinden gebeurt altijd vóór het uitlokken van de statuswijziging. Als anker dat de sessie server-side geregistreerd is, wordt eerst het `serverVersion`-bericht afgewacht; pas daarna wordt de `POST` gedaan. Nergens een vaste `sleep`: wachten gebeurt via `poll(timeout)` met royale time-out en via de bestaande `await{}`-helper. De enige plek waar een korte time-out bewust als "er komt niets" wordt gelezen, is de negatieve assertie in AC2.
- **Testisolatie.** Elke test registreert zijn eigen gebruiker via `registerUser(...)`; alle geopende verbindingen worden in een `@AfterEach` gesloten. `ensureFixedRequests` schrijft via `repo.save` en broadcast dus niet — registreren van een gebruiker levert geen extra WebSocket-berichten op.
- **Tavily-property.** `TavilyClient` doet zonder `app.tavily.api-key` geen HTTP-call; de nieuwe klasse registreert daarom, net als `RequestsE2eTest`, een eigen `@DynamicPropertySource` met een dummy-key zodat de calls bij de `FakeContentServer` uitkomen.
- **Listener-valkuil (JDK).** `WebSocket.Listener.onText` moet zelf `webSocket.request(1)` aanroepen, anders komt na het eerste bericht niets meer binnen; en `onText` levert fragmenten, dus tekst wordt samengevoegd tot `last == true` voor er geparsed wordt. De hulpklasse regelt beide.
- **Ruisbron bij AC3.** `RssScheduler` heeft een hardcoded cron `0 0 * * * *` die niet via een property uit te schakelen is; valt een testrun precies over het hele uur, dan kan er een `hourly-update-*`-broadcast tussendoor komen. Assertie (b) wordt daarom geformuleerd op de berichten die bij het aangemaakte verzoek horen, terwijl assertie (a) ("geen `type`-veld ná `serverVersion`") over álle ontvangen berichten geldt — die blijft correct, ook bij zo'n schedulerbericht.
- **AC6 en het snoeien van dode sessies.** Bij een nette close verwijdert Spring de sessie al in `afterConnectionClosed`; de dead-session-tak in `broadcast` (`RequestWebSocketHandler.kt:45-56`) wordt door deze test dus niet gegarandeerd doorlopen. Het criterium is daarom op waarneembaar gedrag geformuleerd (de overige clients blijven berichten ontvangen), niet op het raken van die codetak.
- **`serverVersion`-waarden.** `BUILD_SHA`/`BUILD_TIME` worden alleen in de Docker-image gezet (`newsfeedbackend/Dockerfile:53-61`), niet in de CI-testjob; `"unknown"` is dus een stabiele verwachting en tegelijk de gedocumenteerde fallback.
- **Geen docs-update nodig.** Deze story legt bestaand, al gespecificeerd gedrag vast; `specs/e2e.md` beschrijft de handmatige Node-scenariosuite, niet de JUnit-e2e-map.

## Eindsamenvatting

Alles gelezen: `.task.md`, het worklog (`docs/stories/worklog/SF-2109-worklog.md`), de diff en de twee nieuwe testbestanden. Hieronder de eindsamenvatting.

---

## SF-2109 — E2e-test voor de WebSocket `/ws/requests`

### Wat is gebouwd
Een audit-story zonder functionele wijziging: het bestaande WebSocket-gedrag van `/ws/requests` is vastgelegd in geautomatiseerde tests, zodat toekomstige wijzigingen aan dat kanaal niet stilzwijgend kunnen doorschuiven. Twee nieuwe bestanden, beide test-only:

- `e2e/WsTestClient.kt` — kleine testclient bovenop de JDK-`WebSocket`: vangt de twee klassieke valkuilen af (zelf `request(1)` aanroepen, en fragmenten samenvoegen tot `last == true`), zet geparsede berichten in een queue met een `poll(timeout)` die `null` geeft bij time-out.
- `e2e/RequestWebSocketE2eTest.kt` — 5 tests die AC1 t/m AC6 dekken: het `serverVersion`-bericht bij connect (precies één, met `sha`/`buildTime`), dat dit bericht géén broadcast is, dat statuswijzigingen van een verzoek als volledige `NewsRequest`-objecten binnenkomen, dat alle clients die berichten krijgen, en dat een netjes gesloten verbinding de overige clients niet blokkeert.

### Gemaakte keuzes
- **Geen productiecode aangeraakt.** De diff is 3 bestanden: 2 onder `src/test/` plus het worklog. Geen `pom.xml`, specs of lockfiles.
- **Geen nieuwe dependencies** — de JDK-`WebSocket` volstond; `/ws/**` is `permitAll`, dus geen token nodig.
- **Geen vaste sleeps.** Wachten gebeurt via `poll(timeout)` en de bestaande `await{}`-helper; het `serverVersion`-bericht dient als anker dat de sessie server-side geregistreerd is vóór de `POST`. De enige twee korte time-outs (2 s) zijn bewuste negatieve asserties.
- **Robuust tegen ruis.** Asserties filteren per verzoek-`id`, zodat een uurlijkse scheduler-broadcast (hardcoded cron, niet uitschakelbaar) de test niet kan omgooien.
- **Bewust gedrag gedocumenteerd.** Dat de server níet per gebruiker filtert staat als commentaarregel met spec-verwijzing in de test — een toekomstige keuze om wél te filteren dwingt nu een expliciete testwijziging af.
- **Eén assertie strenger dan gevraagd:** de statusreeks is exact `PENDING → PROCESSING → DONE` in plaats van alleen "eindigt op DONE"; op het ad-hoc-pad is dat deterministisch, dus geen flake-risico.

### Wat is getest
- `mvn clean verify` tweemaal achter elkaar gedraaid door zowel developer als tester: 4× BUILD SUCCESS, 116 unit + 71 e2e-tests, 0 failures/errors. De 5 nieuwe tests zijn de enige toename (baseline 66 e2e). Geen enkele flake, geen Maven-warnings.
- De tester heeft alle zes gedragscriteria daarnaast **onafhankelijk live nagemeten op de PR-preview** met een eigen WebSocket-client, los van de nieuwe testklasse. Alles kwam overeen; het volledige `NewsRequest`-object (17 velden) gaat inderdaad over de lijn. Het wegwerp-testaccount is opgeruimd en de opruiming is geverifieerd.

### Bewust niet gedaan
- Geen enkele reparatie of aanpassing in productiecode — dat was de kern van de scope-afspraak.
- **Er zijn geen afwijkingen tussen spec en code gevonden**, dus er volgt geen vervolgstory uit deze audit.
- De Flutter-kant (reconnect-logica, client-side tests) blijft buiten scope: die vraagt eerst een injectiepunt en is stof voor een aparte story.
- AC6 is op waarneembaar gedrag geformuleerd, niet op codedekking: bij een nette close ruimt Spring de sessie zelf al op, dus de "dode sessie"-tak wordt hierdoor niet gegarandeerd geraakt. Dat was vooraf zo afgesproken en is expliciet vastgelegd.

**Restpunt (cosmetisch, niet blokkerend):** in `WsTestClient.connect` wordt het socket-veld één keer overbodig opnieuw gezet. Onschadelijk, door de reviewer als suggestie genoteerd.

```json
```
