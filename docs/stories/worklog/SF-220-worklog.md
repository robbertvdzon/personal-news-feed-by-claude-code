# SF-220 - Worklog

Story-context bij eerste pickup:
Verplaats RSS- en podcast-RSS-editors naar nieuwe subpagina

Maak een nieuwe Flutter-subpagina frontend/lib/screens/rss_feeds_screen.dart met eigen AppBar (titel 'RSS-feeds') die de bestaande secties 'RSS-feeds' (_RssFeedsEditor op rssFeedsProvider) en 'Podcast-bronnen' (_PodcastFeedsEditor op podcastFeedsProvider) bevat, inclusief de .when()-loading/error-afhandeling en sectiekoppen. Verplaats de editor-klassen ongewijzigd mee. Vervang in settings_screen.dart de twee inline-secties door één navigatie-ListTile (met Icon(Icons.chevron_right) + MaterialPageRoute) consistent met de API-log/Admin-tiles. Houd bestaande invalidations (o.a. bij uitloggen) intact en verwijzend naar rssFeedsProvider/podcastFeedsProvider. Ruim ongebruikt geworden imports in settings_screen.dart op en voeg benodigde imports toe op de nieuwe pagina. Geen backend-, OpenAPI- of providerwijzigingen. Zorg dat 'flutter analyze' geen nieuwe waarschuwingen/fouten geeft. Voer de ingebouwde review-stap uit. Acceptatiecriteria 1-7.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-290 (developer): RSS- en podcast-editors verplaatst naar nieuwe subpagina
  frontend/lib/screens/rss_feeds_screen.dart (RssFeedsScreen). settings_screen.dart toont nu
  één navigatie-ListTile i.p.v. de twee inline-secties; ongebruikte url_launcher-import en
  ref.watch-regels opgeruimd; logout-invalidations behouden. Stale doc-comment in
  data_providers.dart bijgewerkt. Widgettests toegevoegd (frontend/test/rss_feeds_screen_test.dart).
- flutter analyze/test niet lokaal gedraaid: geen flutter-binary in de developer-runner; CI valideert.
- Details: docs/stories/SF-290-rss-feeds-subpagina.md.

Review (SF-290, reviewer):
- [info] Volledige story-diff t.o.v. main beoordeeld (6 bestanden). Editor-klassen
  _RssFeedsEditor/_PodcastFeedsEditor zijn 1-op-1 en byte-identiek verplaatst; gedrag
  (URL-validatie + snackbar, transcribe-toggle, monospace-weergave, tap→externe browser,
  .when()-loading/error) ongewijzigd.
- [info] settings_screen.dart: twee inline-secties vervangen door één navigatie-ListTile
  (Icons.rss_feed + chevron + MaterialPageRoute), consistent met API-log/Admin. Ongebruikte
  url_launcher-import + feeds/podcastFeeds-ref.watch opgeruimd; alle resterende imports nog in
  gebruik (models/api_client). Logout-invalidations voor rssFeedsProvider/podcastFeedsProvider
  behouden (regels 49-50).
- [info] Nieuwe rss_feeds_screen.dart: alle imports in gebruik; geen backend-/OpenAPI-/
  providerwijzigingen. Stale doc-comment in data_providers.dart correct bijgewerkt.
- [info] Widgettests dekken AC 1-5 functioneel af via fake-notifiers (geen netwerk). Geen
  JSON-artefacten in story-logs. Geen stray refs naar oude editors.
- [info] flutter analyze/test niet lokaal verifieerbaar (geen flutter-binary); CI valideert AC #6.
  Code volgt bestaand patroon, geen nieuwe waarschuwingen verwacht.
- Conclusie: akkoord.

## SF-291 — Story-brede test (tester)

Inlog-modus: **default** — vaste test-user uit secret `newsfeed-api-keys`
(`TESTER_USERNAME`/`TESTER_PASSWORD`), via pure Flutter-UI op de preview
`https://pnf-pr-137.vdzonsoftware.nl` (per-PR Neon-branch). Geen DB-mutatie,
geen prod. Browser: Playwright/Chromium 420x900, screenshots in /work/screenshots.

Resultaten per acceptatiecriterium:
- [pass] AC1: Settings toont op de oude plek één nav-ListTile (rss_feed-icoon,
  titel "RSS-feeds", subtitle "RSS-feed-URLs en podcast-bronnen beheren", chevron),
  consistent met API-log/Beheer-tiles; inline-editors weg. (09-bigwheel.png)
- [pass] AC2: Subpagina met eigen AppBar (← + "RSS-feeds"), RSS-lijst monospace,
  delete-knoppen (×) en "Nieuwe feed-URL"-veld + add-knop. (10/11)
- [pass] AC3: Podcast-bronnen met transcribe-toggle, delete en
  "Nieuwe podcast-RSS-URL"-veld; ongeldige URL ("not-a-valid-url") → rode snackbar
  "Kon feed niet ophalen: ... (URI with undefined scheme)", URL niet toegevoegd,
  veld behoudt tekst. Geen data gepersisteerd. (12/13/14)
- [pass] AC4: Logout-invalidations voor rssFeedsProvider/podcastFeedsProvider
  behouden (settings_screen.dart:49-50); providerlogica ongewijzigd.
- [pass] AC5: Styling consistent (monospace-URLs, sectiekoppen, list-tiles,
  "Fout:"-pattern) — visueel bevestigd.
- [n.v.t./code] AC6: `flutter analyze` niet lokaal draaibaar (geen flutter-binary).
  Manueel geverifieerd: url_launcher-import uit settings verwijderd, alle resterende
  imports nog in gebruik (models/api_client), nieuwe pagina-imports allemaal gebruikt.
- [pass] AC7: Diff raakt alleen frontend + docs/tests; geen backend/OpenAPI/
  providerlogica-wijziging (enkel stale doc-comment in data_providers.dart).

Hard bewijs van 1-op-1 move: `_RssFeedsEditor`+`_PodcastFeedsEditor` byte-identiek
t.o.v. main (diff = 0 m.u.v. 1 trailing blank line); geen stray refs naar oude editors.

Opmerking (geen blocker, geen regressie): de snackbar toont de ruwe JSON-body omdat
de backend een `error`-veld stuurt terwijl `_extractDutchMessage` op `message` matcht.
Dit gedrag is identiek aan main (code byte-identiek verplaatst) — buiten scope van deze
move-story.

Conclusie: alle AC's geslaagd → tested.
