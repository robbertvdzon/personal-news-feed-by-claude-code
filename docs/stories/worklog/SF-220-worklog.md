# SF-220 - Worklog

Story-context bij eerste pickup:
RSS- en podcast-secties naar nieuwe RssFeedsScreen-subpagina

Maak frontend/lib/screens/rss_feeds_screen.dart met RssFeedsScreen (ConsumerWidget, eigen Scaffold+AppBar titel 'RSS-feeds'). Verplaats de inline-secties 'RSS-feeds' (_RssFeedsEditor op rssFeedsProvider) en 'Podcast-bronnen' (_PodcastFeedsEditor, KAN-56, op podcastFeedsProvider) - inclusief hun .when()-loading/error-afhandeling, sectiekoppen en de editor-klassen met state, _save/_extractDutchMessage en doc-comments - uit settings_screen.dart naar het nieuwe bestand; gedrag ongewijzigd (toevoegen/openen via externe browser/verwijderen, synchrone server-URL-validatie met snackbar, transcribeer-toggle). Vervang de twee secties in settings_screen.dart door één navigatie-ListTile (icoon, titel 'RSS-feeds', chevron, MaterialPageRoute → RssFeedsScreen), consistent met de API-log/Beheer-tiles. Verwijder ongebruikt geworden ref.watch-regels en imports in settings_screen.dart en voeg de benodigde imports toe in het nieuwe bestand. Behoud de logout-invalidations (rssFeedsProvider/podcastFeedsProvider) ongewijzigd. Geen wijzigingen aan providers, backend of OpenAPI. Zorg dat flutter analyze schoon is. Voer een zelf-review uit.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw bestand `frontend/lib/screens/rss_feeds_screen.dart` met `RssFeedsScreen`
  (ConsumerWidget, eigen Scaffold + AppBar titel 'RSS-feeds'). Beide secties
  ('RSS-feeds' op `rssFeedsProvider` en 'Podcast-bronnen' op `podcastFeedsProvider`)
  zijn hierheen verplaatst, inclusief de `.when()`-loading/error-afhandeling,
  sectiekoppen en de editor-klassen `_RssFeedsEditor` + `_PodcastFeedsEditor`
  (met state, `_save`/`_extractDutchMessage` en doc-comments). Gedrag ongewijzigd.
- `settings_screen.dart`: de twee inline-secties + beide editor-klassen verwijderd
  en vervangen door één navigatie-`ListTile` (Icon `rss_feed`, titel 'RSS-feeds',
  chevron, `MaterialPageRoute` → `RssFeedsScreen`), consistent met de API-log/Beheer-tiles.
- Opgeruimd in `settings_screen.dart`: ongebruikt geworden `ref.watch(rssFeedsProvider)`/
  `ref.watch(podcastFeedsProvider)`-regels en de `url_launcher`-import. Nieuwe import
  `rss_feeds_screen.dart` toegevoegd. De logout-invalidations voor beide providers
  blijven ongewijzigd staan.
- Geen wijzigingen aan providers, backend of OpenAPI (puur frontend-herstructurering).
- Tests: `frontend/test/rss_feeds_screen_test.dart` toegevoegd met widgettests voor
  de nieuwe subpagina (AppBar-titel + beide secties, RSS-feed toevoegen/verwijderen
  roept `save` aan, podcast transcribeer-toggle slaat aangepaste waarde op) via
  fake-notifiers die `RssFeedsNotifier`/`PodcastFeedsNotifier` overriden.

Validatie:
- De developer-runner heeft GEEN flutter/dart-binary (`which flutter` faalt), dus
  `flutter analyze` en `flutter test` kunnen niet lokaal draaien. De geschreven tests
  en analyze worden door CI gevalideerd. Code is handmatig zelf-gereviewd: geen
  resterende verwijzingen naar de verplaatste editors of ongebruikte imports in
  `settings_screen.dart`, gedrag van de editors is 1-op-1 overgenomen.

## Review (SF-267, reviewer)
- Volledige story-diff `git diff main...HEAD` beoordeeld. Akkoord.
- Editor-extractie naar `rss_feeds_screen.dart` is 1-op-1 (verwijderde = toegevoegde code); gedrag ongewijzigd.
- AC #1-7 gedekt: navigatie-ListTile, beide editors + validatie/toggle/snackbar, logout-invalidations behouden, ongebruikte imports opgeruimd, geen backend/provider/OpenAPI-wijzigingen.
- Tests zinvol (save-aanroepen, toggle, secties). Story-logs vrij van JSON-artefacten.
- [suggestie, niet-blokkerend] dubbele "RSS-feeds"-tekst (sectiekop == tile-titel) in settings_screen.dart.
- flutter analyze/test niet lokaal draaibaar (geen dart-binary); CI valideert.

## Test (SF-268, tester)
Inlog-modus: **wegwerp-flow** (`tester_sf-220` via UI geregistreerd op preview
pnf-pr-134; account aan het eind verwijderd via `DELETE /api/account/me` → HTTP 200).
Geen robbert/DB-mutatie nodig: de wijziging is een pure UI-herstructurering die ook
zonder bestaande data aantoonbaar is.

Verificatie (code + browser, screenshots in /work/screenshots):
- 1-op-1 move hard geverifieerd met diff: `_RssFeedsEditor` byte-identiek t.o.v.
  `main`; `_PodcastFeedsEditor` identiek op één triviale trailing-witregel na. Geen
  resterende referenties naar de editors buiten `rss_feeds_screen.dart`.
- AC#1 ✓ — settings toont één `ListTile` "RSS-feeds" (rss_feed-icoon, subtitle
  "Beheer RSS-feeds en podcast-bronnen", chevron); inline-editors weg (06-settings-scrolled.png).
- AC#2 ✓ — subpagina: RSS-feed toegevoegd, getoond in monospace met verwijder-X
  (08/10-screenshots); add roept `rssFeedsProvider.save` aan.
- AC#3 ✓ — subpagina bevat "Podcast-bronnen"-sectie met input + add-knop; toggle/
  validatie/snackbar-code is 1-op-1 verplaatst (08-rss-subpage.png).
- AC#4 ✓ — logout-invalidations in settings_screen.dart verwijzen nog naar
  `rssFeedsProvider`/`podcastFeedsProvider` (regels 49-50).
- AC#5 ✓ — sectiekoppen, monospace-URL, list-tile-styling consistent (screenshots).
- AC#6 ✓ — `url_launcher`-import uit settings_screen verwijderd; overige imports nog
  in gebruik (CategorySettings/NewsRequest/Event/ApiException); geen ongebruikte imports.
  `flutter analyze` niet lokaal draaibaar (geen dart-binary op runner) → CI valideert.
- AC#7 ✓ — diff raakt alleen frontend-Dart + docs; geen backend/OpenAPI/providerlogica.

Resultaat: geslaagd.
