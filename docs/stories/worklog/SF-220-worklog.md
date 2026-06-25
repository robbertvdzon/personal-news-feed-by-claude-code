# SF-220 - Worklog

Story-context bij eerste pickup:
RSS- en podcast-secties naar nieuwe RssFeedsScreen-subpagina

Maak nieuw bestand frontend/lib/screens/rss_feeds_screen.dart met een ConsumerWidget RssFeedsScreen (eigen Scaffold + AppBar 'RSS-feeds', ListView-body). Verplaats de inline-secties 'RSS-feeds' en 'Podcast-bronnen' inclusief loading-spinner, error-tekst 'Fout: $e', sectiekoppen en de editor-klassen _RssFeedsEditor/_PodcastFeedsEditor uit settings_screen.dart hierheen; gedrag ongewijzigd. Vervang in settings_screen.dart de twee secties door één navigatie-ListTile naar RssFeedsScreen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: write unit/widget tests
[ ]: run flutter analyze/test (niet mogelijk in runner — zie hieronder)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw bestand `frontend/lib/screens/rss_feeds_screen.dart` met `RssFeedsScreen`
  (ConsumerWidget, eigen Scaffold + AppBar 'RSS-feeds', ListView). Bevat de
  ongewijzigd verhuisde sectiekoppen 'RSS-feeds' + 'Podcast-bronnen', de
  `.when()`-loading/error-afhandeling ('Fout: $e') en de editor-klassen
  `_RssFeedsEditor` en `_PodcastFeedsEditor` (incl. monospace-URL, toevoegen/
  openen/verwijderen, synchrone URL-validatie + snackbar, transcribeer-toggle,
  save-calls). Watcht `rssFeedsProvider` + `podcastFeedsProvider`.
- `settings_screen.dart`: de twee inline-secties (RSS-feeds + Podcast-bronnen)
  vervangen door één navigatie-`ListTile` ('RSS-feeds', leading `Icons.rss_feed`
  + `Icon(Icons.chevron_right)`) die via `MaterialPageRoute` naar `RssFeedsScreen`
  pusht — consistent met de bestaande API-log/Admin-tiles. Editor-klassen en de
  bijbehorende lokale watches (`feeds`, `podcastFeeds`) verwijderd.
- Opgeruimd: ongebruikt geworden `url_launcher`-import verwijderd uit
  settings_screen.dart; import van `rss_feeds_screen.dart` toegevoegd. De
  invalidations bij uitloggen (`rssFeedsProvider`/`podcastFeedsProvider`) blijven
  ongewijzigd staan. `models.dart`/`api_client.dart`-imports blijven nodig
  (CategorySettings/NewsRequest/VersionInfo resp. apiProvider/ApiException).
- Geen backend-, OpenAPI- of providerwijzigingen.
- Widgettest toegevoegd: `frontend/test/rss_feeds_screen_test.dart` met fake
  notifiers (overrideWith) — verifieert AppBar-titel + beide sectiekoppen,
  weergave van bestaande feed-URL's, RSS toevoegen/verwijderen (save-calls) en de
  podcast-transcribeer-toggle.

Validatie:
- `flutter analyze`/`flutter test` zijn NIET lokaal gedraaid: de PNF
  developer-runner heeft geen flutter/dart-binary (zie agent-tip
  `pnf-runner-no-flutter`). CI/devcontainer valideert analyze + tests.
  Brace/paren-balans van de gewijzigde bestanden lokaal gecontroleerd; imports
  handmatig nagelopen op ongebruikte symbolen.
