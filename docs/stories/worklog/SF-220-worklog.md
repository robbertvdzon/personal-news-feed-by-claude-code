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

## Review (SF-221) — reviewer

- [info] Volledige story-diff t.o.v. `main` beoordeeld (4 bestanden: nieuwe
  `rss_feeds_screen.dart`, ingekorte `settings_screen.dart`, nieuwe test,
  worklog). Geen backend-, OpenAPI- of providerwijzigingen — scope correct.
- [info] `_RssFeedsEditor` en `_PodcastFeedsEditor` zijn 1-op-1 verhuisd
  (gedrag identiek: monospace-URL, openen extern, verwijderen, transcribeer-
  toggle, synchrone URL-validatie + snackbar, `_busy`-spinner, "Fout: …").
- [info] Imports kloppen: `url_launcher` weg uit settings (niet meer gebruikt),
  `api_client`/`models` blijven terecht (ApiException in `_changePassword`,
  CategorySettings e.d.). Nieuwe screen importeert alles wat het gebruikt.
- [info] Invalidations bij uitloggen (`rssFeedsProvider`/`podcastFeedsProvider`)
  intact en correct. Navigatie-tile consistent met API-log/Admin-patroon.
- [info] `flutter analyze`/`flutter test` niet lokaal draaibaar (geen dart-
  binary in runner); CI valideert. Aanvaardbaar voor pure verplaatsing.
- Akkoord.

## Test (SF-222) — tester

Testmethode: code-inspectie (primair voor PNF — preview is een auth-vereisende
Flutter-canvas-SPA, geen browser-driver/credentials beschikbaar in de runner;
`flutter analyze`/`flutter test` niet draaibaar zonder dart-binary → CI valideert).

Bevindingen per acceptatiecriterium:
- [pass] AC1: settings_screen toont op de oude plek één navigatie-`ListTile`
  ("RSS-feeds", `Icons.rss_feed` + `chevron_right`) die via `MaterialPageRoute`
  naar `RssFeedsScreen` pusht; de inline-editors staan er niet meer (grep:
  geen `_RssFeedsEditor`/`_PodcastFeedsEditor`/`url_launcher` meer in settings).
- [pass] AC2/AC3: beide editor-klassen zijn 1-op-1 verhuisd — `diff` van de
  oude editor-blokken (main) vs `rss_feeds_screen.dart` is byte-identiek
  (alleen `_VersionBlock` blijft, terecht, in settings). RSS-editor
  (toevoegen/openen-extern/verwijderen, monospace-URL) en podcast-editor
  (synchrone URL-validatie + foutsnackbar, transcribeer-toggle, verwijderen,
  `_busy`-spinner) ongewijzigd.
- [pass] AC4: invalidations bij uitloggen (`rssFeedsProvider`/
  `podcastFeedsProvider`, regels 49-50) intact en verwijzen naar de juiste
  providers; providerlogica ongewijzigd.
- [pass] AC5: styling/gedrag identiek (sectiekoppen, monospace-URL, "Fout: …",
  spinner) — overgenomen blokken zijn identiek.
- [n.v.t.-lokaal] AC6: `url_launcher`-import opgeruimd uit settings, toegevoegd
  op nieuwe screen; `api_client`/`models` blijven terecht (ApiException +
  CategorySettings). `flutter analyze` lokaal niet draaibaar → CI.
- [pass] AC7: `git diff --name-only main...HEAD` = uitsluitend frontend + docs;
  geen backend-, OpenAPI- of providerwijzigingen.

Klein, niet-blokkerend (buiten scope, geen defect): `data_providers.dart:207`
bevat een comment die nog naar `settings_screen._PodcastFeedsEditor` verwijst;
de editor woont nu in `rss_feeds_screen.dart`. Puur documentatie, geen gedrag;
AC7 verbiedt providerwijzigingen, dus terecht ongemoeid gelaten.

Conclusie: geslaagd — fase `tested`.
