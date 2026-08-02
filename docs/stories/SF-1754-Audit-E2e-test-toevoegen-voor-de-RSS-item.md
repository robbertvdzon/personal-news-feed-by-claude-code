# SF-1754 - [Audit] E2e-test toevoegen voor de RSS-item-endpoints en de reselect-flow

## Story

[Audit] E2e-test toevoegen voor de RSS-item-endpoints en de reselect-flow

<!-- refined-by-factory -->

## Samenvatting

De RSS-tab heeft een flink aantal endpoints die vandaag nog nergens door een geautomatiseerde test worden aangeraakt: gelezen/ongelezen markeren, sterretjes, duim-omhoog, opruimen, verwijderen en het opvragen van een podcast-transcript. Ook de knop "opnieuw laten selecteren" (reselect) — die de AI opnieuw laat bepalen welke opgeslagen artikelen in de persoonlijke feed horen — is nooit end-to-end getest.

Deze story voegt één nieuwe testklasse toe die al dat gedrag door de echte app heen controleert. Er verandert niets aan de werking van de applicatie zelf; het is puur extra vangnet. Blijkt tijdens het schrijven dat de app zich anders gedraagt dan verwacht, dan wordt dat gemeld en niet stilletjes bijgewerkt.

## Scope

Toevoegen: `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/RssItemsE2eTest.kt`.

- Erft van `E2eTestBase`, volgt `FeedE2eTest.kt` als model (unieke user per test via `registerUser`, HTTP via de helpers `get/post/put/delete`/`getJson`).
- Seed RssItems rechtstreeks via de publieke module-API `RssService.upsert(username, item)`; alleen de twee reselect-tests draaien eerst een echte refresh (die heeft een pipeline-run nodig om representatief te zijn).
- Podcast-afleveringen worden geseed via `PodcastEpisodeRepository.upsert(...)` (autowired, zoals `PodcastIngestE2eTest` doet).
- Geen productiecode-wijziging, geen nieuw testharnas, geen nieuwe fakes, geen wijziging aan bestaande e2e-klassen.
- Buiten scope: `POST /api/rss/refresh` (gedekt door `RssRefreshE2eTest`), `PUT /api/rss-feeds` (gedekt door `SettingsE2eTest`), frontend, documentatie-updates.

## Acceptance criteria

**Item-acties**
1. Na `PUT /api/rss/{id}/read` (200) is `isRead=true` in `GET /api/rss`; na `PUT /api/rss/{id}/unread` weer `false`.
2. `PUT /api/rss/{id}/star` zet `starred=true`; een tweede call zet 'm terug op `false`.
3. `PUT /api/rss/{id}/feedback` met body `{"liked": true}` levert 200 en `liked=true` in `GET /api/rss`.

**markAllRead**
4. `POST /api/rss/markAllRead` geeft 200 met `{"updated": <n>}` waarbij `n` het aantal vóór de call ongelezen items is (dus niet het totaal); daarna zijn alle items `isRead=true`.
5. Een tweede `POST /api/rss/markAllRead` geeft `updated=0`.

**cleanup en delete**
6. Met vier items — oud+gelezen, oud+gelezen+ster, oud+ongelezen, vers+gelezen ("oud" = `timestamp` 50 dagen terug) — verwijdert `DELETE /api/rss/cleanup?olderThanDays=30&keepStarred=true&keepLiked=true&keepUnread=true` precies 1 item (`removed=1`) en blijven de andere drie in `GET /api/rss` staan.
7. `DELETE /api/rss/{id}` geeft 200 en verwijdert precies dat ene item; de overige items blijven onaangetast.

**reselect — happy path**
8. Na een refresh waarbij `ACTION_FEED_SCORE` gescript is om álles af te wijzen (patroon `RssRefreshE2eTest.kt:87-92`) staan de items in `GET /api/rss` met `inFeed=false` en de afwijzingsreden, en is `GET /api/feed` leeg.
9. Wordt `ACTION_FEED_SCORE` daarna gescript op accepteren (met een herkenbaar afwijkende `reason`) en `POST /api/rss/reselect` aangeroepen (200, `{"status":"ok"}`), dan geldt via `await { ... }`: alle items hebben `inFeed=true` met de nieuwe `feedReason`, `GET /api/feed` bevat de nieuw gegenereerde feed-items, en `openAi.callsFor(ACTION_FEED_SUMMARIZE, user)` is niet-leeg (bewijs dat stap 4 echt gedraaid heeft).

**reselect — geen verdicts**
10. Geeft de fake voor `ACTION_FEED_SCORE` een lege lijst (`[]`) terug, dan blijven `inFeed` en `feedReason` van alle bestaande items exact ongewijzigd en komen er geen extra `ACTION_FEED_SUMMARIZE`-calls bij. De test wacht op het extra `ACTION_FEED_SCORE`-call-record als afrondingssignaal (zie aannames) voordat hij de onveranderdheid vaststelt.

**transcript**
11. `GET /api/rss/{id}/transcript` geeft 404 voor een gewoon artikel-item (`mediaType=ARTICLE`, geen podcast-aflevering).
12. Na het seeden van een `PodcastEpisode` met `rssItemId` = het id van een geseed rss-item en een gevulde `transcript`, geeft `GET /api/rss/{id}/transcript` 200 met die transcript-tekst in het veld `transcript`.

**Algemeen**
13. `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend` is groen; failsafe draait `RssItemsE2eTest` mee (naam matcht `**/e2e/*E2eTest.*`).
14. Legt een test afwijkend gedrag van de productiecode bloot, dan wordt dat expliciet gemeld in de PR/worklog in plaats van stilzwijgend in de productiecode gerepareerd of met een slappere assertie weggemoffeld.

## Aannames

- **Item-ids moeten UUID-vorm hebben.** `FakeOpenAiChatClient.extractCandidateIds` vist kandidaat-ids met een UUID-regex uit de selectie-prompt; een geseed item met een id als `"item-1"` levert een lege verdict-lijst op en laat reselect stilzwijgend niets doen. Alle geseede RssItems krijgen daarom `UUID.randomUUID().toString()` als id.
- **Reselect is asynchroon en heeft geen request-status-record** (anders dan refresh, dat een hourly-update-request achterlaat). Voor het "geen verdicts"-pad is er dus geen state-wijziging om op te wachten; als afrondingssignaal gebruikt de test het aantal `ACTION_FEED_SCORE`-calls voor deze user (`openAi.callsFor(...)`), en pas daarna wordt onveranderdheid geassert.
- **Scope-nuance seeden vs. refresh:** de story vraagt enerzijds om direct seeden, anderzijds om een echte refresh in criterium 8/9. Aangenomen: criteria 1-7 en 11-12 seeden direct via `upsert`; de twee reselect-tests draaien eerst een echte refresh (fake-feed via `content.serveArticle`/`rssFeedXml`, zoals `RssRefreshE2eTest.serveDefaultFeed`).
- **cleanup werkt op `RssItem.timestamp`** (niet `processedAt`); "oude" items worden geseed met een expliciet teruggezette `timestamp`. `keepLiked` wordt net als in `FeedE2eTest` expliciet op `true` meegegeven.
- **Transcript-lookup matcht puur op `rssItemId` + niet-lege `transcript`.** `PodcastEpisodeStatus` en `summarySource` van de geseede aflevering zijn irrelevant voor het 200-pad; de test mag daarom een `PodcastEpisode` met minimale velden (`username`, `guid`, `feedUrl`, `rssItemId`, `transcript`) construeren. Merk op dat `specs/openapi.yaml` bij dit endpoint 404 belooft voor `summary_source='show_notes'` terwijl de implementatie daar niet op filtert — dat verschil valt onder criterium 14 (melden), niet onder repareren.
- **Reselect verwijdert geen bestaande FeedItems** bij `inFeed=true → false`; criterium 8 leunt er daarom op dat er in die eerste fase nog nooit een FeedItem is gegenereerd, niet op opruimgedrag.
- Elke test gebruikt een eigen `registerUser(...)`; `E2eTestBase.resetFakes()` wist scripts en call-historie per test, dus scripting lekt niet tussen tests.
- Geen documentatie-updates nodig: `docs/factory/` is compleet en `specs/e2e.md` / `e2e/readme.md` beschrijven de handmatige scenario-suite, niet de JUnit-e2e-klassen.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de branch-diff gelezen. De volledige story-diff t.o.v. `main` bestaat uit precies twee bestanden: de nieuwe testklasse en het worklog — geen productiecode.

---

## Eindsamenvatting SF-1754 — E2e-test voor de RSS-item-endpoints en de reselect-flow

### Wat is gebouwd
Eén nieuwe testklasse: `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/RssItemsE2eTest.kt` (285 regels, 9 tests). Er is **niets aan de werking van de applicatie veranderd** — dit is puur extra vangnet rond gedrag dat tot nu toe door geen enkele geautomatiseerde test werd aangeraakt.

De 9 tests dekken alle 12 functionele acceptatiecriteria:

| Gedrag | Test |
|---|---|
| gelezen/ongelezen markeren | `read en unread markeren` |
| sterretje togglen + duim-omhoog | `ster togglen en feedback zetten` |
| "alles gelezen" (telt alleen vooraf ongelezen items, idempotent) | `markAllRead ...` |
| opruimen met keep-vlaggen (4 items, precies 1 weg) | `cleanup verwijdert oude items ...` |
| los item verwijderen | `item verwijderen laat de andere items staan` |
| reselect happy path (afgewezen items alsnog in de feed) | `reselect promoveert eerder afgewezen items ...` |
| reselect zonder verdicts (selectie blijft ongemoeid) | `reselect zonder verdicts ...` |
| transcript 404 voor artikel / 200 voor podcast | 2 transcript-tests |

### Gemaakte keuzes
- **Snel waar het kan, echt waar het moet:** items worden direct geseed via de publieke module-API `RssService.upsert(...)`; alleen de twee reselect-tests draaien eerst een échte refresh tegen de fake feed-server, omdat reselect anders niet representatief is.
- **Alle geseede item-id's zijn UUID's.** De AI-fake vist kandidaten met een UUID-patroon uit de prompt; met een id als `item-1` zou reselect stilzwijgend niets doen en de test vals-groen zijn.
- **Reselect wacht netjes op de lopende refresh** (één lock per gebruiker), anders zou de reselect stil worden overgeslagen.
- **Geen `sleep`'s** — alle async-verificatie via `await { ... }`, dus geen ingebouwde flakiness of onnodige wachttijd.

### Wat is getest
- `mvn -B clean verify` groen, tweemaal onafhankelijk gedraaid (developer en tester): 94 unit-tests + 61 e2e-tests over 10 klassen, 0 failures / 0 errors. `RssItemsE2eTest` 9/9 groen, ook los gedraaid. Geen flakiness waargenomen.
- Aanvullende smoketest op de preview-omgeving (PR-202) bevestigde de lege-staat-contracten van de endpoints: `markAllRead` → `{"updated":0}`, `cleanup` → `{"removed":0}`, `reselect` → `{"status":"ok"}`, transcript op onbekend id → 404, en 403 zonder token.
- Review is akkoord: alle acceptatiecriteria zijn één-op-één terug te vinden, geen enkele assertie is afgezwakt.

### Bewust niet gedaan
- **Geen productiecode aangepast**, geen nieuwe fakes of testharnas, geen wijziging aan bestaande e2e-klassen — conform de scope.
- **Twee afwijkingen gemeld in plaats van gerepareerd** (zoals de story voorschreef):
  1. `GET /api/rss/{id}/transcript`: `specs/openapi.yaml` belooft 404 bij `summary_source='show_notes'`, maar de code filtert daar niet op en geeft 200. De test legt hier bewust geen assertie op vast — óf de spec óf de code aanpassen is een PO-keuze.
  2. `PUT /api/rss/{onbekend-id}/read` geeft 200 in plaats van 404. Niet in strijd met de spec (die documenteert alleen 200), maar wel stil gedrag bij een niet-bestaand id.
- Endpoints die al elders gedekt zijn (`/api/rss/refresh`, `PUT /api/rss-feeds`), frontend en documentatie vielen buiten scope. Geen browser-screenshots: de story raakt uitsluitend backend-testcode.

### Aandachtspunten voor later (niet blokkerend)
- `serveDefaultFeed()` is gedupliceerd uit `RssRefreshE2eTest`; bij een derde gebruiker hoort die helper in `E2eTestBase`/`FakeContentServer` thuis.
- Bij eventuele toekomstige flakiness: kijk eerst naar het (sub-milliseconde) venster tussen "refresh klaar" en het vrijgeven van de user-lock.
- Het wegwerp-testaccount op de preview kon niet verwijderd worden (JWT verlopen na een pod-herstart). Het account is leeg en verdwijnt automatisch met de per-PR database-branch bij het sluiten van de PR.

---
