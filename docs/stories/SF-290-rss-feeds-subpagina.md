# SF-290 — Verplaats RSS- en podcast-RSS-editors naar nieuwe subpagina

Parent story: SF-220 (RSS-feeds en podcast-RSS naar eigen subpagina van Settings)

## Stappenplan

- [x] Issue + factory-docs + relevante code lezen
- [x] Nieuwe subpagina `frontend/lib/screens/rss_feeds_screen.dart` aanmaken (AppBar "RSS-feeds")
- [x] `_RssFeedsEditor` en `_PodcastFeedsEditor` ongewijzigd meeverhuizen, incl. `.when()`-loading/error + sectiekoppen
- [x] In `settings_screen.dart` de twee inline-secties vervangen door één navigatie-`ListTile` (MaterialPageRoute)
- [x] Ongebruikt geworden imports/ref.watch opruimen (`url_launcher`, `feeds`/`podcastFeeds`-watches)
- [x] Logout-invalidations + providerverwijzingen intact houden
- [x] Stale doc-comment in `data_providers.dart` bijwerken naar nieuwe locatie
- [x] Widgettests schrijven voor de nieuwe subpagina
- [ ] `flutter analyze` / `flutter test` — niet lokaal mogelijk (geen flutter-binary in runner), CI valideert

## Wat is gedaan en waarom

Puur frontend-herstructurering om `settings_screen.dart` korter te maken.

- **Nieuwe subpagina** `RssFeedsScreen` (`ConsumerWidget` + `Scaffold`/`AppBar` titel "RSS-feeds").
  Bevat de secties "RSS-feeds" (`rssFeedsProvider`) en "Podcast-bronnen" (`podcastFeedsProvider`),
  elk met dezelfde `.when()`-loading/error-afhandeling en sectiekoppen als voorheen.
- **Editor-klassen** `_RssFeedsEditor` en `_PodcastFeedsEditor` zijn 1-op-1 (ongewijzigd gedrag)
  verplaatst naar het nieuwe bestand, inclusief de podcast-URL-validatie + snackbar en de
  transcribe-toggle.
- **`settings_screen.dart`**: de twee inline-secties zijn vervangen door één navigatie-`ListTile`
  (`Icon(Icons.rss_feed)` + titel "RSS-feeds" + `Icon(Icons.chevron_right)` + `MaterialPageRoute`),
  consistent met de bestaande API-log/Beheer-tiles. De ongebruikt geworden `ref.watch`-regels en de
  `url_launcher`-import zijn verwijderd; de logout-invalidations voor `rssFeedsProvider`/
  `podcastFeedsProvider` blijven staan.
- **`data_providers.dart`**: stale doc-comment die naar `settings_screen._PodcastFeedsEditor` verwees
  is bijgewerkt naar `rss_feeds_screen._PodcastFeedsEditor`.
- Geen backend-, OpenAPI- of providerwijzigingen.

## Tests

`frontend/test/rss_feeds_screen_test.dart` toegevoegd met fake-notifier-overrides
(geen netwerk), die o.a. controleren: AppBar-titel + beide sectiekoppen, RSS-URL-weergave,
RSS-feed toevoegen (save), lege URL genegeerd, podcast-bron + transcribe-label, toggle uitzetten
en bron verwijderen.

Let op: de developer-runner heeft geen `flutter`/`dart`-binary, dus `flutter analyze` en
`flutter test` kunnen niet lokaal worden gedraaid. Validatie gebeurt via CI.
