# SF-2066 — Gedeelde `kPodcastInProgressStatuses` invoeren en poll-timer bij vertalen fixen

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en de betrokken frontend-bestanden gelezen
- [x] Gedeelde constante `kPodcastInProgressStatuses` toegevoegd in `frontend/lib/models/models.dart`
- [x] `_isInProgress` in `podcast_screen.dart` laten beslissen via de gedeelde set
- [x] Inline lijst in `_maybePoll` (`podcast_screen.dart`) vervangen door de gedeelde set (de eigenlijke fix)
- [x] `_inProgressStatuses` uit `podcast_detail_screen.dart` verwijderd; gebruik wijst naar de gedeelde set
- [x] Nieuwe test `frontend/test/podcast_in_progress_statuses_test.dart`
- [x] `flutter analyze` + `flutter test` in `frontend/` gedraaid
- [x] Backend-vangnet (`mvn -B clean verify`) gedraaid

## Wat is er gedaan en waarom

De lijst met "podcast is nog bezig"-statussen stond op drie plekken los van
elkaar. De kopie in `_maybePoll` miste `TRANSLATING` en `TTS_GENERATING`,
waardoor het overzichtsscherm tijdens het vertalen wél een spinner met
"Vertalen…" toonde maar zichzelf niet meer ververste — het rondje bleef
eindeloos draaien tot je handmatig verversde.

De set staat nu één keer, als top-level `const kPodcastInProgressStatuses`
(`Set<String>`) in `models.dart`, met een comment die vastlegt dat dit de enige
plek is en waarom: spinner/label en poll-timer moeten het per definitie eens
zijn. Alle drie de plekken lezen die ene set, dus ze kunnen niet opnieuw uiteen
lopen. De `if`/`else`-structuur van `_maybePoll` (inclusief het stoppen en op
`null` zetten van de timer) is ongewijzigd, evenals alle statuswaarden,
labelteksten en het polling-interval van 4 seconden.

`Podcast.translationInProgress` houdt bewust zijn eigen, smallere lijst
(`PENDING`/`TRANSLATING`/`TTS_GENERATING`): een vertaling doorloopt nooit de
generatie-statussen. Dat is geen vierde kopie van dezelfde vraag en is dus niet
aangepast; de comment bij de nieuwe constante vermeldt dit expliciet.

De nieuwe unittest legt de zes statussen vast (`_isInProgress`/`_statusLabel`
zijn private en dus niet direct testbaar) en controleert dat `DONE`/`FAILED`
niet als bezig gelden.

## Verificatie

- `flutter analyze` in `frontend/`: 7 issues, alle pre-existing infos
  (`ws_client.dart`, `feed_screen.dart`, `podcast_detail_screen.dart`,
  `rss_detail_screen.dart`, `rss_screen.dart`) — geen nieuwe waarschuwingen,
  geen ongebruikte imports of dode private velden.
- `flutter test` in `frontend/`: 27 tests groen (was 25; +2 uit het nieuwe
  testbestand).
- `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend`: groen (backend
  ongewijzigd, gedraaid als vangnet).
- `frontend/pubspec.lock` is niet gewijzigd.

## Buiten scope gelaten

`_statusLabel`-duplicatie, `_phaseLabel` in `rss_podcast_detail_screen.dart`,
de `feed_screen`/`rss_screen`-duplicatie en backend/openapi/database — conform
de story.
