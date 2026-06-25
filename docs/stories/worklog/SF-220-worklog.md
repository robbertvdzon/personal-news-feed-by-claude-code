# SF-220 - Worklog

Story-context bij eerste pickup:
RSS- en podcast-editors naar nieuwe RssFeedsScreen-subpagina verplaatsen

Maak frontend/lib/screens/rss_feeds_screen.dart met RssFeedsScreen (Scaffold + AppBar 'RSS-feeds') die de secties 'RSS-feeds' (_RssFeedsEditor op rssFeedsProvider) en 'Podcast-bronnen' (_PodcastFeedsEditor op podcastFeedsProvider, KAN-56) host, inclusief .when() loading/error en sectiekoppen. Verhuis beide editor-klassen met ongewijzigd gedrag (synchrone URL-validatie+snackbar, transcribeer-toggle, toevoegen/openen/verwijderen). Vervang in settings_screen.dart de twee inline-secties door één navigatie-ListTile met chevron_right + MaterialPageRoute naar RssFeedsScreen (consistent met ApiLog/Admin-tiles). Ruim ongebruikt geworden imports in settings_screen.dart op en voeg benodigde imports op het nieuwe bestand toe; houd bestaande invalidations (o.a. bij uitloggen) intact en wijzigingsvrij richting de juiste providers. Geen backend-, OpenAPI- of providerwijzigingen. Zorg dat flutter analyze schoon is. Inclusief self-review tegen de acceptatiecriteria (styling: monospace-URL, 'Fout: …'-tekst, spinner).

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests (lokaal niet mogelijk — zie onderaan)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw bestand `frontend/lib/screens/rss_feeds_screen.dart` met `RssFeedsScreen`
  (Scaffold + AppBar 'RSS-feeds'). Beide editor-klassen (`_RssFeedsEditor`,
  `_PodcastFeedsEditor`, KAN-56) zijn 1-op-1 verhuisd inclusief de
  `.when()`-loading/error-takken en de sectiekoppen 'RSS-feeds' en
  'Podcast-bronnen'. Gedrag (monospace-URL-weergave, openen via externe
  browser, verwijderen, transcribeer-toggle, synchrone URL-validatie + snackbar,
  'Fout: …'-error-tekst, spinner) is ongewijzigd overgenomen.
- `settings_screen.dart`: de twee inline-secties zijn vervangen door één
  navigatie-`ListTile` (icoon `rss_feed`, chevron_right, `MaterialPageRoute`
  naar `RssFeedsScreen`), consistent met de bestaande API-log/Admin-tiles.
  De `feeds`/`podcastFeeds` watches en beide editor-klassen zijn verwijderd.
- Imports opgeruimd: ongebruikt geworden `url_launcher` verwijderd uit
  settings_screen; `import 'rss_feeds_screen.dart'` toegevoegd. `models.dart`
  en `api_client.dart` blijven (nog gebruikt door CategorySettings/NewsRequest/
  VersionInfo resp. ApiException in _changePassword).
- Invalidations bij uitloggen (`ref.invalidate(rssFeedsProvider)` /
  `podcastFeedsProvider`) blijven ongewijzigd en verwijzen naar de juiste
  providers. Geen backend-, OpenAPI- of providerwijzigingen.
- Tests: `frontend/test/rss_feeds_screen_test.dart` toegevoegd — verifieert
  AppBar-titel + sectiekoppen, monospace RSS-URL-weergave, podcast-toggle +
  invoervelden, en de loading-spinner-tak (via never-completing providers).

Niet lokaal gedraaid:
- `flutter analyze` / `flutter test` konden in deze runner niet draaien: er is
  geen flutter/dart-binary aanwezig (alleen beschikbaar in `.devcontainer`,
  Flutter 3.35). De wijziging is puur een verplaatsing van bestaande,
  werkende code + opgeruimde imports; CI valideert de feitelijke analyze/test.
