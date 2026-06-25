# SF-220 — RSS-feeds en podcast-RSS naar eigen subpagina van Settings

Subtaak SF-267 (development): RSS- en podcast-secties naar nieuwe `RssFeedsScreen`-subpagina.

## Doel
De settings-pagina (`frontend/lib/screens/settings_screen.dart`) was te lang. Het
beheer van zowel gewone RSS-feed-URL's als podcast-RSS-bronnen verhuist naar één
nieuwe subpagina; op de settings-pagina blijft enkel een navigatieknop achter.

## Stappenplan
- [x] Issue + factory-docs lezen
- [x] Nieuw bestand `rss_feeds_screen.dart` met `RssFeedsScreen` aanmaken
- [x] Beide editors (`_RssFeedsEditor`, `_PodcastFeedsEditor`) + secties verplaatsen
- [x] Settings-secties vervangen door één navigatie-`ListTile`
- [x] Ongebruikte imports/`ref.watch`-regels opruimen, logout-invalidations behouden
- [x] Widgettests schrijven
- [x] Story-log + worklog bijwerken

## Wat is gedaan
- **Nieuw**: `frontend/lib/screens/rss_feeds_screen.dart` — `RssFeedsScreen`
  (ConsumerWidget, eigen Scaffold + AppBar titel "RSS-feeds"). Bevat beide secties
  met hun `.when()`-loading/error-afhandeling, sectiekoppen en de verplaatste
  editor-klassen `_RssFeedsEditor` en `_PodcastFeedsEditor` (state, `_save`,
  `_extractDutchMessage`, doc-comments). Gedrag is 1-op-1 overgenomen: toevoegen,
  openen via externe browser, verwijderen, synchrone server-URL-validatie met
  snackbar en de transcribeer-toggle.
- **Gewijzigd**: `frontend/lib/screens/settings_screen.dart` — de twee inline-secties
  en beide editor-klassen verwijderd; vervangen door één navigatie-`ListTile`
  (Icon `rss_feed`, titel "RSS-feeds", chevron, `MaterialPageRoute` → `RssFeedsScreen`),
  consistent met de bestaande API-log/Beheer-tiles. Ongebruikt geworden
  `ref.watch(rssFeedsProvider)`/`ref.watch(podcastFeedsProvider)`-regels en de
  `url_launcher`-import verwijderd; import van `rss_feeds_screen.dart` toegevoegd.
  De logout-invalidations voor beide providers blijven ongewijzigd.
- **Nieuw**: `frontend/test/rss_feeds_screen_test.dart` — widgettests voor de
  subpagina via fake-notifiers (override van `build()`/`save()`).

Geen wijzigingen aan providers, backend of OpenAPI (`specs/openapi.yaml`).

## Validatie
De developer-runner heeft geen flutter/dart-binary; `flutter analyze`/`flutter test`
draaien niet lokaal en worden door CI gevalideerd. Handmatige zelf-review uitgevoerd:
geen resterende verwijzingen naar de verplaatste editors of ongebruikte imports.
