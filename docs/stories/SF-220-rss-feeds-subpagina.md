# SF-220 - RSS-feeds en podcast-RSS naar eigen subpagina van Settings

Subtaak: SF-274 (development) — Verplaats RSS- en podcast-editors naar nieuwe
RssFeedsScreen-subpagina.

## Stappenplan

- [x] Issue + factory-docs lezen (development.md, technical-spec.md, agent-tips)
- [x] Nieuwe subpagina `RssFeedsScreen` aanmaken met beide editors
- [x] Inline-secties op `settings_screen.dart` vervangen door navigatie-tile
- [x] Ongebruikt geworden imports/watches opruimen, invalidations behouden
- [x] Stale doc-comment in `data_providers.dart` bijwerken
- [x] Widgettests schrijven voor de nieuwe subpagina
- [x] Story-log / worklog bijwerken

## Wat is gedaan en waarom

Doel: de te lange `settings_screen.dart` ontlasten door het beheer van RSS-feeds
en podcast-RSS-bronnen naar één aparte subpagina te verplaatsen. Puur
frontend-herstructurering, geen backend-/API-/providerwijzigingen.

- **Nieuw bestand `frontend/lib/screens/rss_feeds_screen.dart`** met
  `RssFeedsScreen` (`ConsumerWidget`, eigen `Scaffold` + `AppBar` titel
  "RSS-feeds"). De secties "RSS-feeds" en "Podcast-bronnen" — inclusief
  sectiekoppen, `.when()`-loading/error (`Fout: …`) en de editor-klassen
  `_RssFeedsEditor` (op `rssFeedsProvider`) en `_PodcastFeedsEditor` (KAN-56,
  op `podcastFeedsProvider`) — zijn 1-op-1 verhuisd. Gedrag, synchrone
  URL-validatie + snackbar, transcribe-toggle, monospace-URL-weergave en
  externe-browser-tap zijn ongewijzigd overgenomen.
- **`settings_screen.dart`**: beide inline-secties vervangen door één
  navigatie-`ListTile` (Icon `rss_feed` + titel "RSS-feeds" + chevron +
  `MaterialPageRoute` naar `RssFeedsScreen`), consistent met de bestaande
  API-log/Beheer-tiles. De nu ongebruikte `ref.watch(rssFeedsProvider)` /
  `ref.watch(podcastFeedsProvider)` en de `url_launcher`-import (alleen in de
  editors gebruikt) zijn verwijderd. De logout-`invalidate`-aanroepen voor
  beide providers blijven intact.
- **`data_providers.dart`**: stale doc-comment-verwijzing
  `settings_screen._PodcastFeedsEditor` bijgewerkt naar de nieuwe locatie
  `rss_feeds_screen._PodcastFeedsEditor`.
- **Tests** (`frontend/test/rss_feeds_screen_test.dart`): widgettests met fake
  AsyncNotifiers (override `build()`/`save()`, geen netwerk) die controleren
  dat AppBar-titel + sectiekoppen + bestaande bronnen renderen, dat een
  RSS-feed toevoegen/verwijderen `save` aanroept, dat de podcast-toggle de
  transcribe-flag opslaat en dat een podcast verwijderen de lijst leegt.

## Niet lokaal gedraaid

De factory developer-runner heeft geen `flutter`/`dart`-binary, dus
`flutter analyze` en `flutter test` zijn niet lokaal uitgevoerd (CI valideert
deze). Imports zijn handmatig opgeschoond zodat `flutter analyze` geen nieuwe
waarschuwingen zou moeten geven.
