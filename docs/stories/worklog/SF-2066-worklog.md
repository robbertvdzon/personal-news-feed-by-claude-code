# SF-2066 - Worklog

Story-context bij eerste pickup:
Gedeelde kPodcastInProgressStatuses invoeren en poll-timer bij vertalen fixen

Voeg in frontend/lib/models/models.dart een publieke top-level `const kPodcastInProgressStatuses = <String>{'PENDING','DETERMINING_TOPICS','GENERATING_SCRIPT','GENERATING_AUDIO','TRANSLATING','TTS_GENERATING'}` toe, naast het Podcast-model, met een korte comment dat dit de enige plek voor deze lijst is en waarom (spinner/label en poll-timer moeten het per definitie eens zijn). Laat _isInProgress (podcast_screen.dart:16-22) de set gebruiken via contains(). Vervang de inline vierwaarden-lijst in _maybePoll (podcast_screen.dart:56-61) door dezelfde set - dit is de eigenlijke fix; laat de if/else-structuur (:62-68) inclusief timer-cancel ongewijzigd. Verwijder _inProgressStatuses in podcast_detail_screen.dart:30-37 en laat het gebruik op :52 naar de gedeelde set wijzen (KDoc :23-26 behouden, evt. licht aangepast). Voeg één nieuwe test toe in frontend/test/ (bijv. podcast_in_progress_statuses_test.dart) die asserteert dat de gedeelde set exact deze zes statussen bevat, met comment dat spinner en poll-timer dezelfde bron delen. LET OP: Podcast.translationInProgress (models.dart:~497) houdt bewust zijn eigen smallere lijst ['PENDING','TRANSLATING','TTS_GENERATING'] - niet vervangen. Ook niet aanpakken: _statusLabel-duplicatie, _phaseLabel in rss_podcast_detail_screen.dart, feed_screen/rss_screen-duplicatie, backend/openapi/database. Geen wijzigingen buiten de drie genoemde bestanden plus het nieuwe testbestand. Sluit af met een zelfreview en draai in frontend/: flutter analyze (geen nieuwe waarschuwingen, geen ongebruikte imports of dode private velden) en flutter test (alle bestaande tests blijven slagen); zet kale pubspec.lock-drift terug.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-2067 (development) uitgevoerd: gedeelde `kPodcastInProgressStatuses` in
  `frontend/lib/models/models.dart`, gebruikt door `_isInProgress` én `_maybePoll`
  in `podcast_screen.dart` en door het detailscherm (eigen `_inProgressStatuses`
  verwijderd). Daarmee start/houdt de 4-secondentimer nu ook bij `TRANSLATING` en
  `TTS_GENERATING`, zodat de spinner bij vertalen niet meer blijft draaien.
- Nieuw testbestand `frontend/test/podcast_in_progress_statuses_test.dart`.
- Verificatie: `flutter analyze` 7 pre-existing infos (geen nieuwe),
  `flutter test` 27 groen (was 25), backend-vangnet `mvn -B clean verify` groen,
  `frontend/pubspec.lock` ongewijzigd.
- Uitvoerige toelichting staat in
  `docs/stories/SF-2066-gedeelde-podcast-in-progress-statussen.md`.

Review (SF-2067, reviewer):
- Volledige story-diff `git diff main...HEAD` gereviewd: 4 frontend-bestanden +
  story-log + worklog, geen backend/openapi/deploy-wijzigingen. Alle 8
  acceptatiecriteria geverifieerd (AC2 gecheckt met grep: `DETERMINING_TOPICS`
  komt in `frontend/lib/screens/` alleen nog voor in de twee
  `_statusLabel`-switches).
- `Podcast.translationInProgress` (models.dart:517-518) staat nog op de eigen
  smallere lijst — conform scope.
- Eigen gerichte checks: `flutter analyze` = 7 issues, alle pre-existing infos
  (ws_client:20, feed_screen:189, podcast_detail_screen:278, rss_detail_screen:64,
  rss_screen:67/78/228), geen nieuwe; `flutter test` = 27 groen; working tree
  daarna schoon, dus geen `pubspec.lock`-drift.
- Geen blockers of bugs. Besluit: reviewed.
