# SF-2044 - [Audit] E2e-test toevoegen voor de vaste verzoeken: dagelijkse samenvatting en uurlijkse update

## Story

[Audit] E2e-test toevoegen voor de vaste verzoeken: dagelijkse samenvatting en uurlijkse update

<!-- refined-by-factory -->

## Samenvatting

De app heeft twee vaste, automatisch aangemaakte verzoeken: de uurlijkse RSS-update en de dagelijkse samenvatting. Beide kunnen handmatig opnieuw gestart worden. Dat gedrag is vandaag nergens door een test afgedekt.

Deze story voegt een end-to-end-test toe die controleert dat de dagelijkse samenvatting daadwerkelijk wordt gemaakt met het nieuws van de afgelopen periode, dat opnieuw starten geen dubbele samenvatting oplevert, en dat de twee vaste verzoeken netjes gescheiden blijven: het ene start de RSS-ophaalronde, het andere de samenvatting — en nooit allebei.

Er verandert niets aan de werking van de app; dit is puur extra testdekking.

## Scope

Nieuw testbestand `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/FixedRequestsE2eTest.kt`, gemodelleerd naar `RequestsE2eTest.kt`, `FeedE2eTest.kt` en `RssRefreshE2eTest.kt`:

- erft van `E2eTestBase`; per test een eigen gebruiker via `registerUser(...)` (die krijgt via `UserRegisteredListener` automatisch `hourly-update-<user>` en `daily-summary-<user>`);
- seedt feed-items rechtstreeks via `FeedService.save` en rss-items via `RssService.upsert` (publieke module-API) of `RssItemRepository.upsert` — niet via de refresh-pipeline;
- scripts het AI-antwoord met `openAi.onAction(ExternalCall.ACTION_DAILY_SUMMARY) { … }` (die action valt nu in de `else -> "{}"`-tak van `FakeOpenAiChatClient`);
- vangt prompts op via `openAi.callsFor(action, username)`;
- wacht op de `@Async`-listeners met de bestaande `await { … }`-helper;
- injecteert `ExternalCallQuery` voor de Tavily-guard;
- triggert uitsluitend via HTTP: `POST /api/requests/{id}/rerun`, leest via `GET /api/feed`, `GET /api/rss` en `GET /api/requests`.

Buiten scope:

- **Geen productiecode wijzigen.** Legt de test een echte bug bloot, dan wordt die expliciet in het worklog gemeld, niet stilzwijgend gerepareerd — dat is stof voor een aparte story.
- De cron-ingangen `hourlyRefresh()` / `dailySummary()` zelf en hun `@SchedulerLock` (cron staat uit in tests; via de scheduler-proxy testen is een aparte, grotere stap).
- WebSocket-dekking.
- `PUT /api/feed/{id}/read` + `/unread` toevoegen aan `FeedE2eTest` — mag als bijvangst mee als het toch openligt, maar hoort niet in deze testklasse.
- Aanpassingen aan het e2e-harnas (`FakeContentServer`, `FakeOpenAiChatClient`, `E2eTestBase`) zijn niet nodig en niet gewenst; blijkt tijdens implementatie tóch een harnas-uitbreiding nodig, dan additief en zonder bestaand gedrag te wijzigen.

## Acceptance criteria

1. **Happy path dagelijkse samenvatting.** Na het seeden van enkele feed- en rss-items en `POST /api/requests/daily-summary-<user>/rerun` staat in `GET /api/feed` één item met `id = "daily-summary-feed-<vandaag>"`, `isSummary = true`, `title = "Dagelijkse samenvatting <vandaag>"` en `summary` exact gelijk aan de gescripte Markdown-tekst; `<vandaag>` is `LocalDate.now()` in dezelfde vorm als de productiecode gebruikt. Het verzoek `daily-summary-<user>` staat in `GET /api/requests` weer op `status = "DONE"` met `newItemCount = 1` (dat laatste is meteen het `await`-anker, omdat `rerun` `newItemCount` eerst op 0 zet).

2. **De twee tijdvensters** (`RssScheduler.kt:58-59`). Geseed: één feed-item binnen 24 uur en één ruim daarbuiten (bv. 3 dagen oud), plus één rss-item binnen 7 dagen en één ruim daarbuiten (bv. 30 dagen oud). De opgevangen prompt (`openAi.callsFor(ExternalCall.ACTION_DAILY_SUMMARY, username)`, precies één call) bevat de titels van de twee recente items en niét die van de twee oude.

3. **Idempotentie binnen dezelfde dag.** Twee keer `POST /api/requests/daily-summary-<user>/rerun` (de tweede pas nadat de eerste aantoonbaar klaar is, met een per-run onderscheidbaar gescript antwoord) levert in `GET /api/feed` precies één item met `id = "daily-summary-feed-<vandaag>"` op, met de `summary` van de twéede run.

4. **Routering van het rerun-event** (`FixedRequestRerunListener.kt:26-35`), met twee tests die elkaars tegenhanger zijn:
   - Na `POST /api/requests/hourly-update-<user>/rerun` — met een via `FakeContentServer` aangeboden RSS-feed zoals `RssRefreshE2eTest` dat doet — is de RSS-refresh-pipeline aantoonbaar gedraaid (≥1 `ACTION_RSS_SUMMARIZE`- of `ACTION_FEED_SCORE`-call voor deze gebruiker, of `hourly-update-<user>` weer op `DONE`), en is er géén `ACTION_DAILY_SUMMARY`-call voor deze gebruiker.
   - Bij de daily-summary-rerun uit criterium 1 zijn er géén `ACTION_RSS_SUMMARIZE`- en `ACTION_FEED_SCORE`-calls voor deze gebruiker.

5. **Guard tegen dubbele verwerking** (`AdhocOrchestrator.kt:45`). Beide listeners horen naar hetzelfde `RequestRerunEvent`. Na een rerun van een vast verzoek levert `ExternalCallQuery.query(username = <user>, action = ExternalCall.ACTION_TAVILY_SEARCH)` een lege lijst op. Dit wordt pas geassert nadat de betreffende tak aantoonbaar klaar is (het `await`-anker uit criterium 1 resp. 4), zodat de assertie geen race is. Aanvullend: nul `ACTION_ADHOC_SUMMARIZE`-calls voor deze gebruiker.

6. `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend` slaagt (Docker/Testcontainers vereist), zonder nieuwe compile-warnings; alle bestaande tests blijven groen en ongewijzigd.

7. In het worklog staat expliciet vermeld: (a) elke echte bug die de nieuwe test blootlegt, en (b) de constatering dat `feed.delete` op `RssScheduler.kt:75` redundant is ten opzichte van de upsert-semantiek van `FeedItemRepository.save` — als kandidaat voor een aparte opruim-story, niet om nu te wijzigen.

## Aannames

- **De Tavily-guard wordt via `ExternalCallQuery` geassert, niet via `FakeContentServer`.** `FakeContentServer` houdt geen log van binnenkomende requests bij, en zonder `app.tavily.api-key` doet `TavilyClient` überhaupt geen HTTP-call — een assertie op de fake-server zou dus altijd slagen, ook zonder de guard. `TavilyClient.search` schrijft echter óók in de no-key-tak een `external_calls`-rij weg (`PROVIDER_TAVILY` / `ACTION_TAVILY_SEARCH`, status `error`), synchroon via `ExternalCallLoggerImpl`. De assertie "nul tavily_search-rijen voor deze gebruiker" wordt dus rood zodra de `return` op `AdhocOrchestrator.kt:45` wegvalt, ongeacht of er een api-key is gezet. Er is daarom geen extra `@DynamicPropertySource` voor `app.tavily.api-key` nodig.
- **Criterium 3 is een gedragsassertie, geen mutatie-guard.** `FeedItemRepository.save` is een `ON CONFLICT (username, id) DO UPDATE` over alle kolommen; het verwijderen van de `feed.delete`-regel zou de test niet rood maken. De test legt wel het waarneembare gedrag vast (één item, inhoud van de laatste run) en dat is de waarde ervan.
- Bij de tweede run in criterium 3 zit het samenvattings-feed-item van de eerste run zelf binnen het 24-uursvenster en verschijnt het dus in de context van de tweede prompt. Daar wordt niet op geassert; criterium 2 gebruikt een eigen test met één run, zodat de vensterassertie hier geen last van heeft.
- `<vandaag>` wordt in de test afgeleid van `LocalDate.now()` in de test-JVM; test en app draaien in hetzelfde proces, dus een middernacht-race wordt geaccepteerd als verwaarloosbaar (bestaande e2e-suite doet niet anders).
- Test-isolatie komt zoals in de rest van de suite van unieke usernames per test (`E2eTestBase.uniqueUsername`); er wordt niets getruncate.
- Voor criterium 4 volstaat één RSS-feed met één of twee artikelen via `FakeContentServer`, geregistreerd met `PUT /api/rss-feeds`, exact zoals `RssRefreshE2eTest.serveDefaultFeed()` dat doet.

## Eindsamenvatting

Gelezen: `.task.md`, worklog, story-log en de diff (`main..HEAD`: 3 bestanden, alleen test + documentatie).

# SF-2044 — E2e-test voor de vaste verzoeken

## Wat is gebouwd
Eén nieuw testbestand: `newsfeedbackend/.../e2e/FixedRequestsE2eTest.kt` (239 regels, 4 tests), plus story-log en worklog. **Geen productiecode gewijzigd**, geen bestaande test aangepast, e2e-harnas (`E2eTestBase`, `FakeOpenAiChatClient`, `FakeContentServer`) ongewijzigd.

De vier tests dekken samen alle acceptatiecriteria:
1. **Happy path** — rerun van `daily-summary-<user>` levert precies één feed-item `daily-summary-feed-<vandaag>` op met `isSummary = true`, de juiste titel en de gescripte samenvatting; het verzoek staat weer op `DONE` met `newItemCount = 1`.
2. **Tijdvensters** — alleen feed-items van de laatste 24 uur en rss-items van de laatste 7 dagen belanden in de prompt; oudere items niet.
3. **Idempotentie** — twee reruns op dezelfde dag geven één feed-item, met de inhoud van de laatste run.
4. **Routering + guard** — het uurlijkse verzoek start alléén de RSS-refresh, het dagelijkse alléén de samenvatting; de adhoc-tak (tavily/adhoc-summarize) blijft in beide gevallen volledig ongemoeid.

## Belangrijkste keuzes
- Per test een eigen gebruiker; eerst wachten tot de vaste verzoeken zijn aangemaakt (anders geeft rerun een 404).
- Await-anker `status == DONE && newItemCount == 1` — kan niet per ongeluk al vóór de rerun waar zijn, omdat rerun de teller eerst op 0 zet.
- Alle "dit mag níét gebeuren"-asserties staan ná een positief anker, zodat "nog niet gebeurd" niet als "gebeurt nooit" wordt gelezen.
- Venstertest gebruikt UUID-titels, zodat prompt-matching niet op toevallige substrings leunt.
- De adhoc-guard wordt via `ExternalCallQuery` geassert in plaats van via de fake-server, omdat die assertie anders altijd zou slagen.

## Getest
- `mvn clean verify` in `newsfeedbackend/newsfeedbackend`: BUILD SUCCESS, **110 unit + 65 e2e** (was 110 + 61), 0 failures/errors/skipped, 0 warnings. Meerdere volledige runs, allemaal groen; geen flakiness.
- Aanvullend live geverifieerd op de PR-preview met een wegwerp-account (daarna opgeruimd): twee vaste verzoeken bij registratie, daily-rerun geeft één samenvattingsitem, tweede rerun houdt het bij één item met nieuwe inhoud, en de daily-rerun start de RSS-tak niet. Live gedrag komt één-op-één overeen met de test.

## Bewust niet gedaan
- **Geen productiecode gerepareerd of opgeschoond** — de test legde geen echte bug bloot.
- Twee punten expliciet genoteerd als kandidaat voor een aparte story: (a) `feed.delete` in `RssScheduler` is functioneel redundant t.o.v. de upsert-semantiek van de repository; (b) een theoretische race in de laatste teller-assertie van de idempotentietest (reviewer-suggestie, in geen enkele run zichtbaar geworden) en een ongebruikte returnwaarde van een testhelper.
- Buiten scope gebleven: de cron-ingangen zelf en hun scheduler-lock, WebSocket-dekking, en uitbreiding van `FeedE2eTest` met de lees/ongelezen-endpoints.

<!-- deploy-summary:start -->
Er is niets veranderd aan hoe de app werkt of eruitziet. We hebben alleen extra automatische controles toegevoegd die bewaken dat de dagelijkse samenvatting en de uurlijkse nieuwsupdate blijven doen wat ze horen te doen. Daardoor merken we het voortaan direct als een toekomstige wijziging daar iets aan stukmaakt.
<!-- deploy-summary:end -->
