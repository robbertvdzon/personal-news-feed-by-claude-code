# SF-1746 — Events-feature volledig verwijderen

De events-feature werd niet gebruikt maar was met ~85% ($46,26 van $54,33 in juli 2026)
veruit de grootste kostenpost van de app. Alles wat met events te maken had is verwijderd:
backend-module, database-tabellen, API-endpoints, frontend-schermen, tests en documentatie.

Expliciet behouden: Tavily (`search/TavilyClient.kt`, `PNF_TAVILY_API_KEY`, `app.tavily.*`)
voor de ad-hoc nieuws-verzoeken-feature, `ffmpeg` in de Dockerfile voor de podcast-
`AudioTranscoder`, en alle bestaande `external_calls`- en `feed_items`-rijen.

## Stappenplan

- [x] Backend: package `com.vdzon.newsfeedbackend.events` verwijderd (incl. beide `@Scheduled`-schedulers)
- [x] Backend: `settings/` opgeschoond (7 service-methodes, 3 datamodellen, 4 endpoints, 2 repositories, `SettingsDtos.kt`)
- [x] Backend: 5 `ACTION_EVENT_*`-constants, 4 `app.ai.models.event_*`-properties en 2 model-fallbacks weg
- [x] Dockerfile: `python3`/`python3-pip`/`yt-dlp` weg, `ffmpeg` behouden
- [x] Database: `V16__drop_events.sql` (drop `event_videos`, `events`, `event_preferences`, `event_denylist`)
- [x] `specs/openapi.yaml`: 10 paths, 5 schemas en de tag `Events` verwijderd
- [x] Frontend: 2 schermen weg, bottom navigation van 5 → 4 tabs, modellen/providers/settings-tiles weg
- [x] Tests: event-tests verwijderd/uitgekleed, nieuwe widgettest op de 4-tabs-navigatie
- [x] Docs en e2e-scenario's bijgewerkt
- [x] `mvn -B clean verify` (94 unit + 52 e2e, BUILD SUCCESS) en `flutter test` (20 groen)

Volledige uitwerking, motivatie per onderdeel en testresultaten:
[`worklog/SF-1746-worklog.md`](./worklog/SF-1746-worklog.md).
