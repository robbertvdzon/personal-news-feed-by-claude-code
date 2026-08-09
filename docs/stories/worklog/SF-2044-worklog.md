# SF-2044 - Worklog

Story-context bij eerste pickup:
FixedRequestsE2eTest.kt toevoegen voor de twee vaste verzoeken

Voeg newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/FixedRequestsE2eTest.kt toe, erfend van E2eTestBase, gemodelleerd naar RequestsE2eTest/FeedE2eTest/RssRefreshE2eTest. Injecteer FeedService, RssService (of RssItemRepository) en ExternalCallQuery. Per test een eigen gebruiker via registerUser(); seed feed-items met expliciete createdAt via FeedService.save en rss-items met expliciete timestamp via RssService.upsert (niet via de refresh-pipeline); script het AI-antwoord met openAi.onAction(ExternalCall.ACTION_DAILY_SUMMARY); trigger uitsluitend via POST /api/requests/{id}/rerun en lees via GET /api/feed, /api/rss, /api/requests; wacht op de @Async-listeners met de bestaande await{}-helper. Dek de acceptatiecriteria: (1) happy path daily summary - feed-item met id daily-summary-feed-<LocalDate.now()>, isSummary=true, titel 'Dagelijkse samenvatting <vandaag>', summary exact gelijk aan de gescripte Markdown, en daily-summary-<user> weer op DONE met newItemCount=1 (dat is meteen het await-anker, omdat rerun newItemCount eerst op 0 zet); (2) de twee tijdvensters uit RssScheduler.kt:58-59 - één feed-item binnen 24u en één ~3 dagen oud, één rss-item binnen 7d en één ~30 dagen oud; precies één ACTION_DAILY_SUMMARY-call, waarvan de prompt de twee recente titels bevat en de twee oude niet (eigen test met één run); (3) idempotentie binnen dezelfde dag - twee reruns met per-run onderscheidbaar gescript antwoord, de tweede pas nadat de eerste aantoonbaar klaar is, resulterend in precies één feed-item met dat id met de inhoud van de tweede run; (4) routering van het rerun-event (FixedRequestRerunListener.kt:26-35) - na POST /api/requests/hourly-update-<user>/rerun met een via FakeContentServer aangeboden feed (patroon RssRefreshE2eTest.serveDefaultFeed) is de refresh-pipeline aantoonbaar gedraaid (>=1 ACTION_RSS_SUMMARIZE of ACTION_FEED_SCORE, of hourly-update-<user> weer op DONE) en is er geen ACTION_DAILY_SUMMARY; omgekeerd zijn er bij de daily-summary-rerun geen ACTION_RSS_SUMMARIZE/ACTION_FEED_SCORE-calls; (5) guard tegen dubbele verwerking (AdhocOrchestrator.kt:45) - ExternalCallQuery.query(username=<user>, action=ExternalCall.ACTION_TAVILY_SEARCH) is leeg en er zijn nul ACTION_ADHOC_SUMMARIZE-calls, in beide takken pas geassert ná het positieve await-anker zodat het geen race is. Gebruik unieke, onwaarschijnlijke titels voor de venster-asserties zodat prompt-matching niet broos is. Wijzig GEEN productiecode en geen bestaande tests; het e2e-harnas (E2eTestBase, E2eTestConfig, FakeOpenAiChatClient, FakeContentServer) blijft ongewijzigd - blijkt een uitbreiding tóch nodig, dan strikt additief zonder bestaand gedrag te wijzigen. Draai zelf 'mvn -B --no-transfer-progress clean verify' in newsfeedbackend/newsfeedbackend en doe een eigen review-slag op de opgeleverde test (leesbaarheid, geen race-gevoelige asserties, geen nieuwe compile-warnings). Noteer in docs/stories/worklog/SF-2044-worklog.md expliciet: (a) elke echte bug die de nieuwe test blootlegt - melden, niet repareren, dat is stof voor een aparte story - en (b) dat feed.delete op RssScheduler.kt:75 functioneel redundant is t.o.v. de ON CONFLICT (username,id) DO UPDATE-semantiek van FeedItemRepository.save, als kandidaat voor een aparte opruim-story.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-2045 (developer): `FixedRequestsE2eTest.kt` toegevoegd met vier tests die
  samen alle acceptatiecriteria dekken. Details en ontwerpkeuzes staan in
  `docs/stories/SF-2044-e2e-test-vaste-verzoeken.md`. Geen productiecode en
  geen bestaande test gewijzigd; het e2e-harnas is ongewijzigd gebleven.

Verificatie (SF-2045):
- `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`:
  exit 0, 110 unit + 65 e2e (was 110 + 61), 0 failures, 0 errors, 0 `[WARNING]`-regels.
  Twee volledige runs achter elkaar, beide groen — geen flakiness waargenomen.

Bevindingen (criterium 7):

(a) Echte bugs die de nieuwe test blootlegt: **geen**. Alle vier de tests
    slaagden bij de eerste volledige run. Het waargenomen gedrag komt overeen
    met wat de story beschrijft:
    - `daily-summary-<user>/rerun` maakt precies één feed-item
      `daily-summary-feed-<vandaag>` met `isSummary=true` en zet het verzoek
      terug op `DONE` met `newItemCount = 1`;
    - de twee tijdvensters (24 uur voor feed-items, 7 dagen voor rss-items)
      werken zoals in `RssScheduler.kt:58-59` bedoeld;
    - de routering in `FixedRequestRerunListener` is exclusief: het uurlijkse
      verzoek start alleen de RSS-refresh, het dagelijkse alleen de samenvatting;
    - de guard op `AdhocOrchestrator.kt:45` houdt: nul `tavily_search`- en nul
      `adhoc_summarize`-rijen voor een gebruiker die alleen vaste verzoeken
      rerunde.

    Twee observaties die géén bug zijn maar wel het vermelden waard:
    - `RequestServiceImpl.rerun` gooit `NotFoundException` (404) voor een
      onbekend id, dus een rerun-test moet eerst wachten tot
      `UserRegisteredListener` de twee vaste verzoeken heeft aangemaakt.
      Dat is in de test opgevangen met een expliciete `await`.
    - Bij een tweede rerun op dezelfde dag zit het samenvattings-feed-item van
      de eerste run zelf binnen het 24-uursvenster en komt het dus in de context
      van de tweede prompt terecht. Dit is bestaand gedrag; de venster-assertie
      staat daarom in een aparte test met precies één run.

(b) `feed.delete(username, id)` op `RssScheduler.kt:75` is functioneel
    redundant: `FeedItemRepository.save` is een
    `ON CONFLICT (username, id) DO UPDATE` over alle kolommen, dus de
    direct daarop volgende `feed.save(...)` overschrijft de bestaande rij
    sowieso volledig. Het weghalen van die regel zou de nieuwe idempotentie-test
    niet rood maken. Kandidaat voor een **aparte opruim-story**; bewust niet in
    deze story gewijzigd (deze story wijzigt geen productiecode).
