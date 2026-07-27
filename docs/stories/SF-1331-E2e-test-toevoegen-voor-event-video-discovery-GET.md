# SF-1331 - E2e-test toevoegen voor event-video-discovery (GET/POST video-endpoints)

## Story

E2e-test toevoegen voor event-video-discovery (GET/POST video-endpoints)

<!-- refined-by-factory -->

## Scope

Voeg `EventVideosE2eTest.kt` toe aan `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/`, gebouwd op hetzelfde harnas als `EventsE2eTest.kt` (`E2eTestBase`, `FakeContentServer`/`content.tavilySearchJson(...)`, `FakeOpenAiChatClient`/`openAi.onAction(...)`, dynamische `app.tavily.api-key` property).

De test dekt de video-discovery-flow van `EventController`/`EventVideoDiscoveryPipeline`:

1. **Happy path**: registreer een user met event-voorkeur, script een discovery-run (`POST /api/events/discover`) zodat er één event bestaat binnen het discovery-window, script vervolgens Tavily-zoekresultaten + een fake-OpenAI-antwoord voor `ExternalCall.ACTION_EVENT_VIDEO_DISCOVERY` (herkenbaar via `call.subject` dat begint met `"Video's voor event "`) met 1+ video's (`title`, `url`, `description`). Roep `POST /api/events/videos/discover` aan, wacht (`await { ... }`) tot `GET /api/events/{id}/videos` de verwachte video('s) teruggeeft, en verifieer `title`, `videoUrl` (canonieke vorm) en `descriptionNl`.
2. **Dedup op canonieke URL**: voer een tweede `POST /api/events/videos/discover` uit waarbij de AI dezelfde video-URL (evt. met triviale variatie zoals trailing slash/fragment) teruggeeft met een gewijzigde titel/beschrijving. Verifieer dat `GET /api/events/{id}/videos` nog steeds precies 1 video voor die URL bevat en dat de inhoud is bijgewerkt (niet gedupliceerd).
3. **withinWindow-filter**: maak (via discovery of direct via de bestaande event-registratie-flow) een event met een `startDate` buiten het venster (ouder dan 1 jaar terug, geen `null`-datum). Trigger `POST /api/events/videos/discover` en verifieer dat er géén Tavily-call voor dat event plaatsvindt (bv. via een teller/spy op de fake Tavily-server of door te controleren dat er geen `ACTION_EVENT_VIDEO_DISCOVERY`-call voor dat event is gelogd) en dat `GET /api/events/{id}/videos` leeg blijft.

Buiten scope: `POST /{id}/videos/summarize` (vereist eerst fakes voor `YouTubeTranscriptClient`/`WhisperClient`; aparte vervolgstory).

## Acceptance criteria

- Nieuw testbestand `EventVideosE2eTest.kt` in de bestaande `e2e/`-map, gebruikt `E2eTestBase` (geen nieuw testharnas).
- Test 1 (happy path): event aanmaken → `POST /api/events/videos/discover` → `GET /api/events/{id}/videos` bevat de via Tavily + fake-OpenAI (`ACTION_EVENT_VIDEO_DISCOVERY`) ontdekte video(s), met correcte `title`/`videoUrl`/`descriptionNl`.
- Test 2 (dedup): tweede discovery-run met dezelfde (canonieke) video-URL resulteert in één video-record dat is bijgewerkt, niet een tweede record.
- Test 3 (withinWindow): een event met `startDate` ouder dan 1 jaar wordt overgeslagen — geen Tavily-call voor dat event, geen video's opgeslagen.
- `POST /api/events/videos/discover` zonder token → 401/403 (in lijn met bestaand patroon `events-endpoints weigeren zonder token` in `EventsE2eTest.kt`); mag als losse test of als toevoeging aan een bestaande no-token-test.
- `/videos/summarize` wordt niet getest in deze story.
- Alle nieuwe tests slagen lokaal (`mvn test`/`mvn verify` conform `development.md`); bestaande e2e-tests blijven groen.
- Geen wijzigingen aan productiecode (`EventVideoDiscoveryPipeline`, `EventController`, etc.) — dit is een pure test-toevoeging, tenzij een bug wordt ontdekt (dan expliciet melden i.p.v. stilzwijgend fixen).

## Aannames

- De AI-actie voor video-discovery wordt herkend via `call.subject` dat begint met `"Video's voor event "` (afgeleid uit `EventVideoDiscoveryPipeline.kt:133`), analoog aan hoe `EventsE2eTest.scriptDiscovery` op `call.subject` matcht.
- Voor test 3 (withinWindow) wordt een event met een datum > 1 jaar in het verleden gebruikt via directe manipulatie/seed in plaats van via de normale discovery-flow (die alleen toekomstige/recente events accepteert) — een geschikte constructie (bv. rechtstreeks via `EventRepository`/`EventPersister` in de testsetup, indien in-process bereikbaar, of via een aangepaste discovery-scriptrun) wordt door de developer gekozen, mits het resultaat is: een event bestaat met `startDate` buiten het venster vóórdat `POST /api/events/videos/discover` wordt aangeroepen.
- "Geen Tavily-call" wordt geverifieerd door te controleren dat er geen ontdekte video's verschijnen én (indien haalbaar) door de fake Tavily-server niet te scripten voor dat event zodat een onverwachte call zou falen/leeg resultaat geven — een expliciete call-teller op de Tavily-fake is niet vereist als het testharnas die nog niet biedt.
- `docs/factory/` is al compleet (bevestigd, SF-220) — geen extra acceptatiecriterium voor documentatie nodig.

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summary-finished"}
