# SF-1746 - Worklog

Story-context bij eerste pickup:
Events-feature volledig verwijderen (backend, database, API, frontend, tests, docs)

De gerefinede scope in `.task.md` is leidend boven de originele storytekst. Tavily
(`search/TavilyClient.kt`, `PNF_TAVILY_API_KEY`, `app.tavily.*`) blijft bestaan voor de
ad-hoc nieuws-verzoeken-feature; `ffmpeg` blijft in de Dockerfile voor de podcast-
AudioTranscoder; bestaande `external_calls`- en `feed_items`-rijen blijven staan.

## Stappenplan (developer SF-1747)

- [x] Issue + factory-docs + specs lezen
- [x] Backend: package `com.vdzon.newsfeedbackend.events` verwijderd
- [x] Backend: `settings/` opgeschoond (service, impl, controller, dto's, repositories)
- [x] Backend: event-action-constants uit `external_call/ExternalCall.kt`
- [x] Backend: event-modelconfig uit `application.properties` + `AiModelProperties.FALLBACKS`
- [x] Dockerfile: `python3`/`python3-pip`/`pip3 install yt-dlp` weg, `ffmpeg` behouden
- [x] Database: `V16__drop_events.sql` toegevoegd (bestaande migraties ongewijzigd)
- [x] `specs/openapi.yaml`: tien event-paths, vijf schemas en de tag `Events` verwijderd
- [x] Frontend: schermen, tab, modellen, providers en settings-tiles verwijderd
- [x] Tests: event-tests verwijderd/uitgekleed + nieuwe 4-tabs-widgettest
- [x] Docs + e2e-scenario's bijgewerkt
- [x] Vangnet gedraaid: `mvn -B clean verify` en `flutter test`

## Wat is er gedaan en waarom

**Backend.** Het hele package `events` (service, api, domain incl. beide `@Scheduled`-
schedulers, infrastructure) is verwijderd. In `settings/` zijn de zeven event-methodes,
de datamodellen `EventPreferences`/`EventDenylistEntry`/`EventDenylist`, de vier
event-endpoints in `SettingsController`, `SettingsDtos.kt` (bevatte alleen de twee
event-request-DTO's) en `EventPreferencesRepository`/`EventDenylistRepository` weg.
De vijf `ACTION_EVENT_*`-constants zijn uit `ExternalCall.kt` gehaald; de vier
`app.ai.models.event_*`-properties uit `application.properties` plus de twee bijbehorende
fallbacks in `AiModelProperties`. Bewust behouden: `search/TavilyClient.kt`,
`PNF_TAVILY_API_KEY`, `app.tavily.*`, `ACTION_TAVILY_SEARCH`/`ACTION_TAVILY_EXTRACT`
en alle Spring-application-events (`AuthEvents`/`RssEvents`/`RequestEvents`/
`PodcastSourceEvents`).

**Dockerfile.** `python3`, `python3-pip` en de `pip3 install yt-dlp`-regel zijn weg —
`yt-dlp` werd alleen door de verwijderde `VideoAudioDownloader` gebruikt. De
podcast-audio-download gebruikt een gewone HTTP-download, dus `ffmpeg` blijft staan
voor `AudioTranscoder`/`Mp3Concatenator`.

**Database.** `V16__drop_events.sql` (V15 bestond al) dropt in FK-veilige volgorde
`event_videos`, `events`, `event_preferences` en `event_denylist` met `DROP TABLE IF
EXISTS`. V11–V14 zijn niet aangeraakt (staan al in prod); `external_calls` en
`feed_items` blijven ongemoeid zodat de historische kosten en de eerder door de
`EventFeedAnnouncer` aangemaakte items zichtbaar blijven.

**API.** Uit `specs/openapi.yaml` zijn de tien event-paths, de schemas
`EventPreferences`/`EventDenylistEntry`/`EventDenylist`/`Event`/`EventVideo` en de tag
`Events` verwijderd. Gecontroleerd: geen dangling `$ref`, top-level structuur intact,
en het voorbeeld bij `ExternalCall.action` verwijst niet meer naar `event_discovery`.

**Frontend.** `events_screen.dart` en `event_detail_screen.dart` verwijderd; de bottom
navigation gaat van vijf naar vier tabs (Feed/RSS/Podcast/Instellingen). `Event` en
`EventVideo` uit `models.dart`, `eventsProvider`/`EventsNotifier`, `eventVideosProvider`
en `requestVideoSummary` uit `data_providers.dart`, en `_EventDiscoveryTile`/
`_EventVideoDiscoveryTile` uit `settings_screen.dart` (de sectie *Achtergrond-taken*
zelf blijft, met nog twee rijen). `frontend-reader/` bevatte geen event-referenties.

**Tests (eigen ontwikkelwerk).** `EventsE2eTest.kt`, `EventVideosE2eTest.kt` en
`events/VideoAudioDownloaderArgsTest.kt` verwijderd. Uit `SettingsE2eTest.kt` zijn de
preferences-/denylist-tests en de twee event-paden uit de auth-endpointlijst gehaald;
uit `ApiRequestDtoContractTest.kt` de drie event-DTO-tests. Ook de twee
`SettingsServiceImpl*Test`-klassen moesten mee (de constructor heeft twee parameters
minder). In `frontend/test/main_shell_test.dart` zijn `_FakeEventsNotifier` en de
`eventsProvider`-override weg, en is er één nieuwe test bij: *"bottom-navigatie heeft
vier tabs zonder Events (SF-1746)"* — die pint het nieuwe navigatiecontract vast.
Het e2e-harnas (`E2eTestBase`, `FakeContentServer` incl. de Tavily-helpers,
`FakeOpenAiChatClient`) is ongewijzigd; `RequestsE2eTest` slaagt zonder aanpassingen.

**Docs.** Bijgewerkt: `README.md`, `runbook.md` (o.a. tabellenlijst, migraties t/m V16,
`yt-dlp` uit de stack, het `SELECT … FROM events`-voorbeeld), `specs/README.md`,
`specs/backend-functional-spec.md` (§6.8–6.10 weg, tabellen, model-defaults,
Tavily-beschrijving, config-tabel, geplande taken), `specs/backend-technical-spec.md`
(moduletabel, `search`-omschrijving, metrics, testoverzicht), `specs/frontend-spec.md`
(navigatieboom + §8b weg, Settings is nu Tab 3), `specs/e2e.md`, `e2e/readme.md`,
`e2e/scenarios/settings-scenario.md`, `docs/factory/{README,functional-spec,
technical-spec,development}.md`, `docs/factory/agents/tester.md` en
`docs/onboarding-senior-developer.md`. `e2e/scenarios/events-scenario.md` is verwijderd.
`docs/kwaliteitsanalyse-backend.md` is bewust ongewijzigd gelaten.

## Testresultaten

- `mvn -B clean verify` (`newsfeedbackend/newsfeedbackend`): **BUILD SUCCESS**,
  94 unit-tests + 52 e2e-tests, 0 failures, 0 errors, 0 skipped (~2:42 min).
  `ModuleStructureTest` (lege allowlist) blijft groen.
- `flutter test` (`frontend/`): **20 tests groen** (was 19; de nieuwe 4-tabs-test erbij).
- `flutter analyze` (`frontend/`): 7 meldingen, allemaal pre-existing `info`-niveau in
  ongewijzigde bestanden; geen nieuwe.
- `frontend/pubspec.lock` en `frontend-reader/pubspec.lock` zijn niet gewijzigd.

## Acceptatiecriteria

1. ✅ Geen `@Scheduled`-cron meer voor event- of event-video-discovery (beide
   schedulers verwijderd; alleen RSS-, daily-summary- en podcast-recovery-crons over).
2. ✅ `grep -ri event` op backend/frontend levert alleen nog Spring-application-events,
   `AuthEvents`/`RssEvents`/`RequestEvents`/`PodcastSourceEvents`, het woord "eventueel"
   en de historische migraties V11–V14 op.
3. ✅ Ad-hoc nieuws-verzoeken gebruiken nog steeds Tavily; `PNF_TAVILY_API_KEY` blijft
   verplicht (`RequestsE2eTest` ongewijzigd groen).
4. ✅ Migraties draaien schoon: de e2e-suite start elke testklasse tegen een verse
   Testcontainers-Postgres met de volledige Flyway-set t/m V16; `DROP TABLE IF EXISTS`
   maakt de migratie ook veilig op de prod-database waar de tabellen wél bestaan.
5. ✅ Backend- en e2e-tests slagen.
6. ⏳ Te verifiëren na deploy (kostenscherm) — code-zijdig kunnen er geen
   `tavily_search`-event-calls, `event_discovery`- of `event_video_discovery`-rijen
   meer ontstaan; historische rijen zijn niet aangeraakt.

## Review (SF-1747, reviewer)

Code, API, database, frontend en tests zijn integraal nagelopen tegen de volledige
story-diff (`git diff main...HEAD`, 63 bestanden). Bevindingen:

- Geen dangling `$ref` in `specs/openapi.yaml`; tag `Events` weg, schema-set exact
  gedekt (geen ongebruikte schema's, geen onbekende referenties).
- Geen enkele verwijzing meer naar verwijderde symbolen (`EventPreferences`,
  `EventDenylist*`, `eventsProvider`, `ACTION_EVENT_*`, `VideoAudioDownloader`,
  `YouTubeTranscriptClient`) in `.kt`/`.dart`/`.yaml`/`.properties`.
- `V16__drop_events.sql` volgt het naampatroon, raakt geen bestaande migratie aan en
  dropt FK-veilig (`event_videos` vóór `events`) met `IF EXISTS`.
- Behoud geverifieerd: `TavilyClient`/`app.tavily.*`/`PNF_TAVILY_API_KEY`, `ffmpeg`
  in de Dockerfile en alle Spring-application-events.

Twee doc-restanten die na deze wijziging feitelijk onjuist zijn (terug naar developer):

- [bug] `runbook.md:136` — "multi-stage: Maven/JDK21 → Temurin JRE21 + ffmpeg + yt-dlp",
  terwijl `yt-dlp`/`python3` in dezelfde diff uit de Dockerfile zijn gehaald.
- [bug] `docs/factory/technical-spec.md:71` — de `ProcessBuilder`-codeconventie noemt als
  enige voorbeeld "de video-URL aan `yt-dlp` in `VideoAudioDownloader`"; die klasse
  bestaat niet meer. Conventie zelf blijft geldig, alleen het voorbeeld moet weg/vervangen.
