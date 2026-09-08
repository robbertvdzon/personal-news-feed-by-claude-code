# SF-1331 - Worklog

Story-context bij eerste pickup:
EventVideosE2eTest.kt implementeren (happy path, dedup, withinWindow, no-token)

Voeg newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/EventVideosE2eTest.kt toe, gebouwd op E2eTestBase/FakeContentServer/FakeOpenAiChatClient zoals EventsE2eTest.kt. Dek: (1) happy path - event via POST /api/events/discover binnen window, fake-OpenAI response scripten voor ExternalCall.ACTION_EVENT_VIDEO_DISCOVERY (call.subject begint met "Video's voor event "), POST /api/events/videos/discover aanroepen, await tot GET /api/events/{id}/videos de video(s) teruggeeft, assert title/videoUrl (canoniek)/descriptionNl; (2) dedup - tweede discovery-run met dezelfde (canonieke) URL en gewijzigde titel/beschrijving resulteert in exact 1 bijgewerkt video-record; (3) withinWindow - event met startDate >1 jaar in het verleden (niet-null), buiten de normale discovery-flow geseed (bv. via repository/persister in testsetup), GET /api/events/{id}/videos blijft leeg na POST /api/events/videos/discover, geen fake-OpenAI/Tavily scripting voor dat event zodat een onverwachte call zou falen; (4) POST /api/events/videos/discover zonder token → 401/403. Geen wijzigingen aan productiecode; ontdekte bugs expliciet melden i.p.v. stilzwijgend fixen. /videos/summarize blijft buiten scope. Lokaal mvn test/mvn verify draaien en laten slagen inclusief bestaande e2e-tests.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw `EventVideosE2eTest.kt` toegevoegd in `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/`,
  gebouwd op `E2eTestBase`/`FakeContentServer`/`FakeOpenAiChatClient`, exact zoals `EventsE2eTest.kt`. Vier tests:
  1. Happy path: event via `POST /api/events/discover` (fake Tavily + fake-OpenAI `event_discovery`), daarna
     `POST /api/events/videos/discover` met gescripte Tavily-video-search + fake-OpenAI-antwoord voor
     `ExternalCall.ACTION_EVENT_VIDEO_DISCOVERY` (subject-check op `"Video's voor event "`); `await` tot
     `GET /api/events/{id}/videos` de video teruggeeft en `title`/`videoUrl`/`descriptionNl` kloppen.
  2. Dedup: tweede discovery-run met dezelfde canonieke URL (trailing fragment als triviale variatie) en
     gewijzigde titel/beschrijving; geverifieerd dat er precies 1 video-record blijft en dat de inhoud is
     bijgewerkt (niet gedupliceerd).
  3. withinWindow: event met `startDate` 2 jaar terug rechtstreeks via `EventRepository.upsert` geseed (de
     normale discovery-flow accepteert zulke datums niet, zie `EventPersister.withinWindow` — vandaar directe
     repository-seed, conform de Aannames in de story). Na `POST /api/events/videos/discover` blijft
     `GET /api/events/{id}/videos` leeg en is er geen `ACTION_EVENT_VIDEO_DISCOVERY`-call gelogd voor die user.
  4. `POST /api/events/videos/discover` zonder token → 401/403, analoog aan het bestaande patroon in `EventsE2eTest`.
- Geen wijzigingen aan productiecode; er zijn geen bugs ontdekt tijdens het schrijven/draaien van de tests.
  `/videos/summarize` blijft buiten scope, zoals gevraagd.
- Getest: Docker/Testcontainers was in deze run beschikbaar. `mvn -q verify` (volledige suite, unit + e2e via
  failsafe) draaide groen: 102 tests, 0 failures, 0 errors (surefire + failsafe reports samen), inclusief de
  4 nieuwe tests in `EventVideosE2eTest` en de bestaande `EventsE2eTest`.
