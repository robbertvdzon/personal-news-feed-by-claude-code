# SF-220 - Worklog

Story-context bij eerste pickup:
RSS- en podcast-secties naar nieuwe RssFeedsScreen-subpagina

Maak een nieuwe subpagina frontend/lib/screens/rss_feeds_screen.dart (RssFeedsScreen, ConsumerWidget) met eigen Scaffold + AppBar('RSS-feeds') en daarin de twee sectiekoppen 'RSS-feeds' en 'Podcast-bronnen' met hun feeds.when(...) / podcastFeeds.when(...)-loading/error/data-afhandeling exact zoals nu in settings_screen.dart (regels 96-108). Verhuis de editor-klassen _RssFeedsEditor en _PodcastFeedsEditor (incl. state-klassen, KAN-56-comment, _save/_extractDutchMessage/busy-logica) ongewijzigd mee naar dit bestand; behoud rssFeedsProvider/podcastFeedsProvider. Verwijder in settings_screen.dart de twee inline-secties (incl. Dividers/koppen) en de bijbehorende lokale watches (regels 22-23), en vervang ze door één navigatie-ListTile (icoon, titel 'RSS-feeds', chevron_right, onTap → Navigator.push MaterialPageRoute naar RssFeedsScreen) consistent met de API-log/Beheer-tiles. Behoud de invalidations bij uitloggen (rssFeedsProvider/podcastFeedsProvider, regels 51-52). Ruim ongebruikt geworden imports op en voeg ontbrekende imports toe op beide bestanden zodat flutter analyze schoon is. Vul docs/factory/development.md, functional-spec.md en technical-spec.md aan met concrete repo-/stack-info (AC #8). Geen backend-, OpenAPI- of providerwijzigingen. Sluit af met een eigen review van de diff.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw scherm `frontend/lib/screens/rss_feeds_screen.dart` (`RssFeedsScreen`,
  ConsumerWidget, eigen Scaffold + AppBar 'RSS-feeds') met de twee sectiekoppen
  'RSS-feeds' en 'Podcast-bronnen' en hun `.when()`-loading/error/data-afhandeling,
  exact zoals voorheen in `settings_screen.dart`.
- Editor-klassen `_RssFeedsEditor` en `_PodcastFeedsEditor` (incl. state-klassen,
  KAN-56-comment, `_save`/`_extractDutchMessage`/busy-logica) ongewijzigd verhuisd naar
  het nieuwe bestand. Providers (`rssFeedsProvider`/`podcastFeedsProvider`) ongewijzigd.
- In `settings_screen.dart`: de twee inline-secties (koppen + `.when()`) vervangen door
  één navigatie-`ListTile` (Icons.rss_feed, titel 'RSS-feeds', chevron_right,
  `MaterialPageRoute` → `RssFeedsScreen`), consistent met de API-log/Beheer-tiles.
  Ongebruikt geworden `ref.watch(rssFeedsProvider)`/`podcastFeedsProvider`-watches en de
  `url_launcher`-import verwijderd; `rss_feeds_screen.dart`-import toegevoegd. De
  logout-invalidations (rssFeedsProvider/podcastFeedsProvider) blijven behouden.
- Stale doc-comment in `data_providers.dart` bijgewerkt naar de nieuwe locatie
  (`rss_feeds_screen._PodcastFeedsEditor`).
- Geen backend-, OpenAPI- of providerlogica-wijzigingen.
- Tests: nieuw `frontend/test/rss_feeds_screen_test.dart` met widgettests (fake-notifier-
  overrides) voor: beide sectiekoppen/feeds tonen, RSS-feed toevoegen/verwijderen,
  podcast-bron toevoegen (transcribe aan), transcribe-toggle en snackbar bij ongeldige URL.
- `flutter analyze`/`flutter test` zijn niet lokaal gedraaid: de developer-runner heeft
  geen flutter/dart-binary. Validatie gebeurt door CI (zie docs/factory/development.md).
- `docs/factory/development.md`, `functional-spec.md` en `technical-spec.md` gevuld met
  concrete repo-/stack-info.
