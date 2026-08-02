# SF-1754 - Worklog

Story-context bij eerste pickup:
RssItemsE2eTest.kt toevoegen (item-acties, markAllRead, cleanup/delete, reselect, transcript)

Voeg newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/RssItemsE2eTest.kt toe, ervend van E2eTestBase en gemodelleerd op FeedE2eTest.kt (unieke user per test via registerUser, HTTP via get/post/put/delete/getJson). Autowire RssService (seed via upsert(username, item)) en PodcastEpisodeRepository (seed van podcast-afleveringen). Dek: (1) PUT /api/rss/{id}/read en /unread, PUT /{id}/star twee keer togglen, PUT /{id}/feedback met {"liked": true} - elke wijziging teruglezen via GET /api/rss; (2) POST /api/rss/markAllRead geeft het aantal vooraf ongelezen items terug en is idempotent (tweede call 0); (3) DELETE /api/rss/cleanup?olderThanDays=30&keepStarred=true&keepLiked=true&keepUnread=true met vier items (oud+gelezen, oud+ster, oud+ongelezen, vers; 'oud' = timestamp 50 dagen terug) verwijdert precies 1 item, plus DELETE /api/rss/{id} verwijdert precies dat ene item; (4) twee reselect-tests die eerst een echte refresh draaien tegen de fake feed-server (patroon RssRefreshE2eTest.serveDefaultFeed): happy path met ACTION_FEED_SCORE eerst alles afwijzend (feed leeg), daarna accepterend met afwijkende reason, POST /api/rss/reselect (200, status ok) en via await verifiëren dat items inFeed=true met de nieuwe feedReason krijgen, GET /api/feed de nieuwe feed-items toont en openAi.callsFor(ACTION_FEED_SUMMARIZE, user) niet-leeg is; plus het 'geen verdicts'-pad met een lege lijst, waarbij op het extra ACTION_FEED_SCORE-call-record wordt gewacht als afrondingssignaal voordat onveranderdheid van inFeed/feedReason en het uitblijven van extra summarize-calls wordt geassert; (5) GET /api/rss/{id}/transcript geeft 404 voor een ARTICLE-item en 200 met de transcript-tekst na het seeden van een PodcastEpisode met rssItemId + gevuld transcript. Let op: alle geseede RssItem-ids MOETEN UUID.randomUUID().toString() zijn, anders vindt FakeOpenAiChatClient.extractCandidateIds niets en doet reselect stilzwijgend niets. Gebruik await{} in plaats van sleeps. Geen productiecode-wijziging, geen nieuw harnas, geen nieuwe fakes, geen wijziging aan bestaande e2e-klassen. Legt een test afwijkend productiegedrag bloot (o.a. de bekende drift tussen specs/openapi.yaml en PodcastTranscriptLookupImpl rond summary_source='show_notes'), meld dat expliciet in PR/worklog in plaats van het te repareren of met een slappere assertie weg te moffelen. Sluit af met een zelfreview van de nieuwe testklasse en draai lokaal mvn -B verify in newsfeedbackend/newsfeedbackend. Werk docs/stories/worklog/SF-1754-worklog.md bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## Gedaan

Toegevoegd: `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/RssItemsE2eTest.kt`
(9 tests, erft van `E2eTestBase`, gemodelleerd op `FeedE2eTest`):

1. `read en unread markeren` — AC 1.
2. `ster togglen en feedback zetten` — AC 2 + 3.
3. `markAllRead telt alleen de vooraf ongelezen items en is idempotent` — AC 4 + 5.
4. `cleanup verwijdert oude items maar respecteert keep-vlaggen` — AC 6 (4 items, `timestamp`
   50 dagen terug, `removed=1`, de andere drie blijven staan).
5. `item verwijderen laat de andere items staan` — AC 7.
6. `reselect promoveert eerder afgewezen items alsnog naar de feed` — AC 8 + 9 (echte refresh
   met `ACTION_FEED_SCORE` alles-afwijzend, daarna accepterend met een afwijkende reason,
   `POST /api/rss/reselect` (200, `status=ok`), via `await`: alle items `inFeed=true` met de
   nieuwe `feedReason`, 2 feed-items in `GET /api/feed` en niet-lege
   `ACTION_FEED_SUMMARIZE`-calls).
7. `reselect zonder verdicts laat de bestaande selectie ongemoeid` — AC 10 (fake geeft `[]`;
   wacht op het extra `ACTION_FEED_SCORE`-call-record als afrondingssignaal en vergelijkt dan
   de complete (id → inFeed/feedReason)-map van vóór en na, plus het uitblijven van extra
   summarize-calls).
8. `transcript geeft 404 voor een gewoon artikel` — AC 11.
9. `transcript geeft de tekst van de gekoppelde podcast-aflevering` — AC 12 (`PodcastEpisode`
   geseed via de autowired `PodcastEpisodeRepository`).

Implementatiekeuzes, conform de aannames in de story:
- Items worden direct geseed via de publieke module-API `RssService.upsert(username, item)`;
  alleen de twee reselect-tests draaien eerst een echte refresh tegen `FakeContentServer`
  (`serveDefaultFeed`, patroon uit `RssRefreshE2eTest`).
- Alle geseede id's zijn `UUID.randomUUID().toString()` — anders vindt
  `FakeOpenAiChatClient.extractCandidateIds` (UUID-regex) niets en doet reselect stil niets.
- De reselect-tests wachten eerst tot de hourly-update-request `DONE` is voordat ze reselect
  triggeren: `RssRefreshPipeline` gebruikt één lock per user en zou een reselect tijdens een
  nog lopende refresh stilzwijgend overslaan (`tryLock`).
- Geen `Thread.sleep`; alle async-verificatie via `await { ... }`.
- Geen productiecode-wijziging, geen nieuwe fakes/harnas, geen wijziging aan bestaande
  e2e-klassen.

## Vangnet

`mvn -B clean verify` in `newsfeedbackend/newsfeedbackend`: **BUILD SUCCESS**, unit-suite +
61 e2e-tests, 0 failures / 0 errors (~4:06 min). `RssItemsE2eTest`: 9 tests groen — failsafe
pikt 'm op via `**/e2e/*E2eTest.*` (AC 13).

## Afwijkend productiegedrag (AC 14 — melden, niet repareren)

- **`GET /api/rss/{id}/transcript` en `summary_source='show_notes'`**: `specs/openapi.yaml`
  (~regel 486) belooft 404 "als er geen transcript is (niet-podcast item of
  `summary_source='show_notes'`)", maar `PodcastTranscriptLookupImpl` filtert alleen op
  `rssItemId` + een niet-leeg `transcript`; de `summarySource` van de aflevering speelt geen
  rol. Een aflevering met `summarySource='show_notes'` én een gevuld transcript krijgt dus 200
  in plaats van de gedocumenteerde 404. De testklasse legt hier bewust géén assertie op vast
  (dat zou één van beide kanten stilzwijgend bevriezen); dit is spec-vs-code-drift die buiten
  de scope van deze test-story valt — spec aanpassen óf het filter toevoegen is een PO-keuze.
- Voor het overige gedroeg de app zich exact zoals de acceptatiecriteria beschrijven; geen
  enkele assertie is afgezwakt.

## Review (SF-1761)

Akkoord. Volledige story-diff t.o.v. `main` bestaat uit precies twee bestanden
(`RssItemsE2eTest.kt` + deze worklog); geen productiecode, geen wijziging aan bestaande
e2e-klassen, fakes of harnas. AC 1-12 zijn één-op-één terug te vinden in de 9 tests en de
asserties kloppen met `RssServiceImpl.markAllRead/cleanup` en `RssRefreshPipeline.reselect`.
Testbewijs geverifieerd in `target/failsafe-reports`: 10 e2e-klassen, 61 tests, 0 failures /
0 errors; `RssItemsE2eTest` 9/9 groen (AC 13). De openapi-drift rond `summary_source` is
correct gemeld en niet weggemoffeld (AC 14).

Niet-blokkerende observaties voor een volgende story:
- `awaitRefreshDone` wacht op requeststatus `DONE`, die in `RssRefreshPipeline.run()` wordt
  weggeschreven vlak vóór `lock.unlock()` in de `finally`. Theoretisch kan een reselect die
  in dat (sub-milliseconde) venster binnenkomt alsnog op `tryLock` afketsen en stil niets
  doen; de test faalt dan pas op de 30s-timeout. Praktisch verwaarloosbaar t.o.v. de
  HTTP-roundtrip, maar het is de eerste plek om te kijken bij toekomstige flakiness.
- `serveDefaultFeed()` is bewust gedupliceerd uit `RssRefreshE2eTest` (die versie is private
  en mocht niet gewijzigd worden). Bij een derde gebruiker is een gedeelde helper in
  `FakeContentServer`/`E2eTestBase` de betere plek.

## Test (SF-1762)

Geverifieerd op branch `ai/SF-1754` (diff t.o.v. `main` = 2 bestanden: `RssItemsE2eTest.kt`
+ deze worklog; geen productiecode).

**Vangnet — `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend`: exitcode 0, BUILD SUCCESS**
(3:11 min). Surefire 94 tests, failsafe 61 tests over 10 e2e-klassen — 0 failures / 0 errors.
`RssItemsE2eTest`: 9/9 groen, opgepikt door failsafe via `**/e2e/*E2eTest.*` (AC 13).
Daarvóór apart gedraaid met `-Dit.test=RssItemsE2eTest`: eveneens 9/9 groen. Twee onafhankelijke
runs, geen flakiness waargenomen.

**AC-dekking nagelopen** in de testklasse: AC 1 (`read en unread markeren`), AC 2+3 (`ster togglen
en feedback zetten`), AC 4+5 (`markAllRead ...`, verwacht `updated=2` bij 2 van 3 ongelezen — telt
dus niet het totaal), AC 6 (`cleanup ...`, `removed=1`, de andere drie blijven), AC 7 (`item
verwijderen ...`), AC 8+9 (`reselect promoveert ...`), AC 10 (`reselect zonder verdicts ...`),
AC 11+12 (transcript 404 / 200). Geen afgezwakte asserties aangetroffen.

**Preview-smoketest** (`https://pnf-pr-202.vdzonsoftware.nl`, wegwerp-account via de API):
`GET /api/rss` 200 `[]`, `POST /api/rss/markAllRead` 200 `{"updated":0}`,
`DELETE /api/rss/cleanup?olderThanDays=30&keepStarred=true&keepLiked=true&keepUnread=true`
200 `{"removed":0}`, `POST /api/rss/reselect` 200 `{"status":"ok"}`,
`GET /api/rss/{onbekend-uuid}/transcript` 404, `GET /api/feed` 200 `[]`,
`GET /api/rss` zonder token 403. De lege-staat-contracten komen overeen met wat de
acceptatiecriteria en de nieuwe tests vastleggen.

**Observaties (niet blokkerend, niet in deze story te repareren):**
- De openapi-drift rond `summary_source='show_notes'` bij `GET /api/rss/{id}/transcript` is door
  de developer correct gemeld (AC 14) en niet weggemoffeld — bevestigd.
- `PUT /api/rss/{onbekend-uuid}/read` geeft op preview 200 in plaats van 404. `specs/openapi.yaml`
  documenteert bij dit endpoint alleen een 200 en geen 404, dus dit is geen spec-schending, maar
  wél stil gedrag bij een niet-bestaand id. Buiten de scope van deze story; geen van de AC's raakt
  dit pad.

**Opruimen preview:** het wegwerp-account `tester_sf-1754` kon niet verwijderd worden. De
`DELETE /api/account/me`-call gaf 403 (het JWT was ongeldig geworden, waarschijnlijk door een
herstart van de backend-pod) en het wachtwoord was op dat moment al opgeruimd. Het account is leeg
(geen feeds, items of instellingen; alleen lees-calls gedaan) en leeft in de per-PR Neon-branch
`pr-202`, die bij PR-close automatisch wordt opgeruimd. De `TESTER_USERNAME`/`TESTER_PASSWORD`
uit `newsfeed-api-keys` waren niet leesbaar (de `claude-agent`-SA mag geen secrets lezen in
`pnf-pr-202`), vandaar de wegwerp-modus.

**Geen browser-screenshots:** de story raakt uitsluitend backend-testcode, geen frontend.
