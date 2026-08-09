# SF-2044 — E2e-test voor de vaste verzoeken (dagelijkse samenvatting en uurlijkse update)

Subtaak SF-2045 (development): `FixedRequestsE2eTest.kt` toevoegen.

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en de bestaande e2e-suite gelezen
- [x] Productiecode bestudeerd: `RssScheduler`, `FixedRequestRerunListener`,
      `AdhocOrchestrator`, `RequestServiceImpl.rerun`, `TavilyClient`
- [x] `FixedRequestsE2eTest.kt` toegevoegd (4 tests, alle acceptatiecriteria)
- [x] `mvn -B --no-transfer-progress clean verify` gedraaid (twee keer groen)
- [x] Worklog bijgewerkt met bevindingen (bugs + `feed.delete`-observatie)

## Wat is er gedaan en waarom

Nieuw bestand
`newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/FixedRequestsE2eTest.kt`,
gemodelleerd naar `RequestsE2eTest`/`FeedE2eTest`/`RssRefreshE2eTest`. Geen
productiecode en geen bestaande test gewijzigd; het e2e-harnas is ongewijzigd
gebleven (er was geen uitbreiding nodig).

Vier tests:

1. `rerun van het dagelijkse verzoek maakt een samenvattings-feed-item en zet het verzoek op DONE`
   — happy path (criterium 1), plus de negatieve kant van criterium 4
   (geen `rss_summarize`/`feed_score`) en criterium 5 (geen `tavily_search`,
   geen `adhoc_summarize`).
2. `de dagelijkse samenvatting krijgt alleen feed-items van 24 uur en rss-items van 7 dagen mee`
   — de twee tijdvensters uit `RssScheduler.kt:58-59` (criterium 2), met één
   run zodat het samenvattings-item van een vorige run de prompt niet vervuilt.
3. `twee reruns op dezelfde dag leveren een samenvatting op, met de inhoud van de laatste run`
   — idempotentie binnen dezelfde dag (criterium 3).
4. `rerun van het uurlijkse verzoek start de RSS-refresh en niet de dagelijkse samenvatting`
   — routering van het rerun-event (criterium 4) + de guard (criterium 5).

Ontwerpkeuzes die de tests niet-flaky houden:

- Elke test heeft een eigen gebruiker (`registerUser`), en wacht eerst tot
  `UserRegisteredListener` de twee vaste verzoeken heeft aangemaakt — zonder
  die wacht geeft `POST /api/requests/{id}/rerun` een 404.
- Await-anker voor de daily-tak: `status == DONE && newItemCount == 1`.
  `rerun` zet `newItemCount` eerst op 0, dus dit anker kan niet per ongeluk
  al vóór de rerun waar zijn.
- Alle negatieve asserties (criterium 4 en 5) staan *na* een positief
  await-anker, zodat "nog niet gebeurd" en "gebeurt nooit" niet verwisseld
  kunnen worden.
- In de happy-path-test is bewust wél een RSS-feed geregistreerd (zonder te
  refreshen): zou het rerun-event óók de refresh-pipeline starten, dan worden
  de nul-asserties op `rss_summarize`/`feed_score` daadwerkelijk rood.
- De venster-assertie gebruikt unieke titels met een UUID erin, zodat
  prompt-matching niet op toevallige substrings berust.

## Verificatie

`mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`:
exit 0, **110 unit-tests + 65 e2e-tests** (was 110 + 61), 0 failures, 0 errors,
0 `[WARNING]`-regels. Twee keer achter elkaar gedraaid, beide keren groen.
