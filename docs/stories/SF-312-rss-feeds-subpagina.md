# SF-312 — RSS- en podcast-secties naar nieuwe subpagina RssFeedsScreen

Parent story: SF-220 — RSS-feeds en podcast-RSS naar eigen subpagina van Settings.

## Doel
De lange `settings_screen.dart` opsplitsen: de inline-secties "RSS-feeds"
(`_RssFeedsEditor`) en "Podcast-bronnen" (`_PodcastFeedsEditor`) verhuizen naar
één nieuwe subpagina `RssFeedsScreen`. Op de settings-pagina blijft alleen een
navigatie-`ListTile` achter. Puur frontend-herstructurering.

## Stappenplan
- [x] Bestaande code + factory-docs lezen (`settings_screen.dart`, providers, tips).
- [x] Nieuw bestand `frontend/lib/screens/rss_feeds_screen.dart` met eigen
      `Scaffold`/`AppBar` (titel "RSS-feeds") + verplaatste editors en `.when()`.
- [x] In `settings_screen.dart` de twee secties vervangen door één navigatie-tile.
- [x] Ongebruikte imports/`ref.watch`-regels opruimen in `settings_screen.dart`.
- [x] Logout-invalidations en overige providers intact laten.
- [x] Stale doc-comment in `data_providers.dart` bijwerken naar nieuwe locatie.
- [x] Widgettests schrijven voor de nieuwe subpagina + navigatie.
- [ ] `flutter analyze`/`flutter test` lokaal — N.B. geen flutter-binary in runner
      (zie tip `pnf-runner-no-flutter`); CI valideert.

## Wat & waarom
- De editor-klassen (`_RssFeedsEditor`, `_PodcastFeedsEditor`) zijn 1-op-1
  meeverhuisd naar `rss_feeds_screen.dart`; gedrag ongewijzigd. `RssFeedsScreen`
  is een `ConsumerWidget` die `rssFeedsProvider`/`podcastFeedsProvider` watcht en
  dezelfde sectiekoppen + loading/error-afhandeling toont als voorheen.
- Op `settings_screen.dart` vervangt één `ListTile` (icoon `Icons.rss_feed`,
  titel "RSS-feeds", chevron) de twee secties; navigatie via `MaterialPageRoute`
  consistent met de API-log/Beheer-tiles.
- De `url_launcher`-import en de `feeds`/`podcastFeeds` `ref.watch`-regels zijn
  uit `settings_screen.dart` verwijderd (alleen door de editors gebruikt). De
  logout-invalidations en alle overige providers blijven ongewijzigd.
