# SF-1746 - Events-feature volledig verwijderen (Tavily blijft voor ad-hoc search)

## Story

Events-feature volledig verwijderen (Tavily blijft voor ad-hoc search)

## Waarom
De events-feature wordt niet gebruikt maar is verreweg de grootste kostenpost. Meting op de prod-tabel `external_calls` (juli 2026): $46,26 van de $54,33 totaal = 85% van alle externe API-kosten. Alle 8.469 Tavily-calls ooit zijn event-queries. Zonder events kost de app ~$8/maand.

Kosten juli 2026:
- tavily_search (100% events, zondag 02:00 + 03:00): $38,10
- openai event_video_discovery: $6,26
- openai event_discovery: $1,90

## Doel
Alles wat met events te maken heeft uit de applicatie verwijderen: code, database, API, frontend, tests en documentatie.

## BELANGRIJK — wat MOET blijven
- `search/TavilyClient.kt` en de env-var `PNF_TAVILY_API_KEY` BLIJVEN bestaan. De ad-hoc nieuws-verzoeken-feature (`request/domain/AdhocOrchestrator.kt:54`) gebruikt Tavily en die feature blijft in gebruik.
- `ffmpeg` in de Dockerfile blijft (nodig voor de podcast-AudioTranscoder).
- Bestaande feed-items die ooit door de EventFeedAnnouncer zijn aangemaakt mogen blijven staan; ze mogen alleen niet meer aangemaakt worden.

## Scope — backend
Verwijderen:
- Hele package `com.vdzon.newsfeedbackend.events`:
  - `events/EventService.kt`, `events/domain/EventServiceImpl.kt`
  - `events/domain/EventScheduler.kt` (cron `0 0 2 * * SUN`), `events/domain/EventVideoScheduler.kt` (cron `0 0 3 * * SUN`)
  - `events/domain/EventDiscoveryPipeline.kt`, `EventVideoDiscoveryPipeline.kt`, `EventVideoSummaryPipeline.kt`
  - `events/domain/EventExtractor.kt`, `EventDateEnricher.kt`, `EventPersister.kt`, `EventFeedAnnouncer.kt`
  - `events/infrastructure/EventRepository.kt`, `EventVideoRepository.kt`, `VideoAudioDownloader.kt`, `YouTubeTranscriptClient.kt`
  - `events/api/EventController.kt`, `events/api/dto/EventDtos.kt`
- Uit `settings/`: `getEventPreferences`/`saveEventPreferences`/`addEventPreference`/`removeEventPreference`/`getEventDenylist`/`addEventToDenylist`/`removeEventFromDenylist` uit `SettingsService.kt` + `SettingsServiceImpl.kt`, de datamodellen `EventPreferences`/`EventDenylist`/`EventDenylistEntry`, en `settings/infrastructure/EventPreferencesRepository.kt` + `EventDenylistRepository.kt`
- Uit `external_call/ExternalCall.kt`: de action-constants `ACTION_EVENT_DISCOVERY`, `ACTION_EVENT_VIDEO_DISCOVERY`, `ACTION_EVENT_VIDEO_SUMMARIZE`, `ACTION_EVENT_VIDEO_AUDIO_DOWNLOAD`, `ACTION_EVENT_VIDEO_TRANSCRIPT_FETCH`
- Uit de Dockerfile: de `yt-dlp` + `python3`/`python3-pip` installatie (alleen gebruikt door VideoAudioDownloader). Controleer eerst dat de podcast-audio-download geen yt-dlp gebruikt. ffmpeg laten staan.
- Event-gerelateerde config uit `application.properties`

Endpoints die weg moeten (zie `specs/openapi.yaml`):
- `/api/events`, `/api/events/discover`, `/api/events/videos/discover`, `/api/events/{id}`, `/api/events/{id}/videos`, `/api/events/{id}/videos/summarize`
- `/api/settings/event-preferences`, `/api/settings/event-preferences/remove`, `/api/settings/event-denylist`, `/api/settings/event-denylist/{normalizedId}`

## Scope — database
Nieuwe Flyway-migratie (bv. `V15__drop_events.sql`) die de tabellen dropt: `events`, `event_videos`, `event_preferences`, `event_denylist`. De oude migraties V11/V12/V13/V14 NIET aanpassen (die zijn al toegepast in prod) — alleen een nieuwe drop-migratie toevoegen.

## Scope — frontend
- `frontend/lib/screens/events_screen.dart` en `event_detail_screen.dart` verwijderen
- Events-tab uit de bottom navigation (`main_shell.dart`) en routes uit `main.dart` / `deep_link.dart`
- Event-modellen uit `models/models.dart`, event-providers uit `providers/data_providers.dart`
- Event-onderdelen uit `settings_screen.dart` (voorkeuren + denylist)
- `frontend-reader` controleren op event-referenties

## Scope — tests en docs
- `test/.../e2e/EventsE2eTest.kt`, `EventVideosE2eTest.kt`, `events/VideoAudioDownloaderArgsTest.kt` verwijderen
- `e2e/scenarios/events-scenario.md` verwijderen
- Event-stubs uit `e2e/FakeContentServer.kt` / `E2eTestBase.kt` opruimen voor zover niet meer gebruikt (Tavily-stub blijft nodig voor `RequestsE2eTest`)
- Documentatie bijwerken: `README.md`, `runbook.md`, `specs/README.md`, `specs/backend-functional-spec.md`, `specs/backend-technical-spec.md` (o.a. moduletabel + `search`-beschrijving die nu 'ad-hoc/events-discovery' zegt), `specs/frontend-spec.md`, `specs/openapi.yaml`

## Acceptatiecriteria
1. Er staan geen `@Scheduled`-crons meer die event-discovery of event-video-discovery starten.
2. `grep -ri "event" backend/frontend` levert alleen nog domein-vreemde treffers op (Spring application events, `AuthEvents.kt`, `RssEvents.kt`, `RequestEvents.kt`, `PodcastSourceEvents.kt` — die gaan over interne events en blijven).
3. De ad-hoc nieuws-verzoeken-feature werkt nog en gebruikt nog steeds Tavily; `PNF_TAVILY_API_KEY` blijft vereist.
4. De app start op met een schone database én met de bestaande prod-database (migratie draait door).
5. Backend-tests en e2e-tests slagen.
6. In het admin-kostenscherm verschijnen na deploy geen nieuwe `tavily_search`, `event_discovery` of `event_video_discovery` calls meer. Historische rijen in `external_calls` blijven staan.

## Eindsamenvatting

Geverifieerd tegen de checkout: `events`-package weg, `V16__drop_events.sql` aanwezig, geen event-crons meer.

# SF-1746 — Events-feature volledig verwijderen (eindsamenvatting)

## Wat is gebouwd
De events-feature is in z'n geheel uit de applicatie verwijderd — de grootste kostenpost (~85% van de externe API-kosten in juli 2026, $46,26 van $54,33). Diff: 63 bestanden, ~4.478 regels verwijderd tegenover ~357 toegevoegd.

- **Backend** — hele package `com.vdzon.newsfeedbackend.events` weg (service, API, discovery-/video-pipelines, repositories én beide `@Scheduled`-zondagcrons). `settings/` opgeschoond: 7 service-methodes, 3 datamodellen, 4 endpoints, 2 repositories. 5 `ACTION_EVENT_*`-constants en de `app.ai.models.event_*`-config verwijderd.
- **Database** — nieuwe migratie `V16__drop_events.sql` (V15 bestond al) dropt FK-veilig `event_videos`, `events`, `event_preferences`, `event_denylist` met `DROP TABLE IF EXISTS`. V11–V14 zijn niet aangeraakt.
- **API** — 10 event-paths, 5 schemas en de tag `Events` uit `specs/openapi.yaml`; geen dangling `$ref`.
- **Frontend** — beide event-schermen weg, bottom navigation van 5 naar 4 tabs (Feed/RSS/Podcast/Instellingen), modellen/providers/settings-tiles verwijderd.
- **Infra** — `python3`/`yt-dlp` uit de Dockerfile (werd alleen door de verwijderde `VideoAudioDownloader` gebruikt).
- **Docs** — README, runbook, alle specs, e2e-scenario's en factory-docs bijgewerkt; `events-scenario.md` verwijderd.

## Belangrijkste keuzes
- **Tavily blijft** (`TavilyClient.kt`, `PNF_TAVILY_API_KEY`, `app.tavily.*`): de ad-hoc nieuws-verzoeken-feature gebruikt die nog.
- **`ffmpeg` blijft** in de Dockerfile voor de podcast-`AudioTranscoder`; gecontroleerd dat de podcast-audiodownload gewoon HTTP gebruikt, geen yt-dlp.
- **Historische data blijft staan**: `external_calls` (kostenhistorie) en oude feed-items van de EventFeedAnnouncer worden niet opgeruimd — alleen niet meer aangemaakt.
- **Migratie nieuw i.p.v. bestaande aanpassen**, zodat de prod-database gewoon doormigreert.
- Spring-application-events (`AuthEvents`, `RssEvents`, `RequestEvents`, `PodcastSourceEvents`) blijven — die gaan over interne events.

## Wat is getest
- **Build/unit/e2e**: `mvn -B clean verify` → BUILD SUCCESS, 94 unit- + 52 e2e-tests, 0 failures. `flutter test` → 20 groen (incl. nieuwe widgettest die de 4-tabs-navigatie vastpint). `flutter analyze`: geen nieuwe meldingen.
- **Live op preview** (`pnf-pr-200`, image = branch-HEAD): ad-hoc verzoek "Kotlin 2.3 release" → 201, binnen 6 s `DONE` met 1 feed-item via Tavily. Flyway valideerde 16 migraties op een kopie van de prod-database, schemaversie 16, app start schoon op. Alle oude event-endpoints gedragen zich als niet-bestaande paden. Oude deeplink `/events` valt netjes terug op de Feed.
- **Review** (2 rondes): twee doc-restanten over `yt-dlp`/`VideoAudioDownloader` gevonden en gefixt; daarna akkoord zonder openstaande bevindingen.

## Bewust niet gedaan / aandachtspunten
- **AC 6 (kostenscherm) is pas na deploy te bevestigen**: code-zijdig kunnen er geen nieuwe `tavily_search`-event-, `event_discovery`- of `event_video_discovery`-calls meer ontstaan, maar het bewijs in het admin-kostenscherm volgt na productie-deploy.
- Historische story-/worklogbestanden die `yt-dlp` noemen zijn bewust ongewijzigd gelaten (verslagen, geen actuele docs); `docs/kwaliteitsanalyse-backend.md` idem.
- **Pre-existing non-blocker, buiten scope**: onbekende `/api/**`-paden geven 500 i.p.v. 404 (`NoResourceFoundException` valt in de generieke handler). Bestaat ook op `main` — geen regressie, wel eventueel een losse story waard.

Volgende stappen in de story: documentatie-subtaak (SF-1750), merge (SF-1751) en deploy (SF-1752).
