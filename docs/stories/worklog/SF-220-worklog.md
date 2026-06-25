# SF-220 - Worklog

Story-context bij eerste pickup:
Verplaats RSS- en podcast-editors naar nieuwe RssFeedsScreen-subpagina

Maak nieuw bestand frontend/lib/screens/rss_feeds_screen.dart met RssFeedsScreen (Scaffold + AppBar 'RSS-feeds'). Verhuis de inline-secties 'RSS-feeds' (_RssFeedsEditor, rssFeedsProvider) en 'Podcast-bronnen' (_PodcastFeedsEditor, podcastFeedsProvider) uit settings_screen.dart naar deze subpagina, inclusief sectiekoppen, .when()-loading/error-afhandeling en de editor-klassen. Vervang de twee secties op settings_screen.dart door één navigatie-ListTile met chevron + MaterialPageRoute, consistent met de API-log/Admin-tiles. Ruim ongebruikt geworden imports op en houd de uitlog-invalidations (rssFeedsProvider/podcastFeedsProvider) en benodigde watches intact. Geen backend-/API-/OpenAPI-/providerwijzigingen. Behoud exact gedrag en styling. Voeg waar zinvol unit-/widgettests toe als onderdeel van dit ontwikkelwerk.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Nieuw bestand `frontend/lib/screens/rss_feeds_screen.dart` aangemaakt met
  `RssFeedsScreen` (`ConsumerWidget`, eigen `Scaffold` + `AppBar` titel
  "RSS-feeds"). De twee secties (RSS-feeds + Podcast-bronnen), hun
  `.when()`-loading/error-afhandeling, sectiekoppen en de editor-klassen
  (`_RssFeedsEditor`, `_PodcastFeedsEditor`) zijn 1-op-1 verhuisd; gedrag,
  validatie en styling ongewijzigd.
- `settings_screen.dart`: de twee inline-secties vervangen door één
  navigatie-`ListTile` (Icon `rss_feed` + titel "RSS-feeds" + chevron +
  `MaterialPageRoute` naar `RssFeedsScreen`), consistent met de
  API-log/Beheer-tiles. Ongebruikt geworden `ref.watch(rssFeedsProvider)` /
  `ref.watch(podcastFeedsProvider)` en de `url_launcher`-import verwijderd.
  De logout-invalidations voor beide providers blijven intact.
- Stale doc-comment in `data_providers.dart` bijgewerkt:
  `settings_screen._PodcastFeedsEditor` -> `rss_feeds_screen._PodcastFeedsEditor`.
- Widgettests toegevoegd in `frontend/test/rss_feeds_screen_test.dart`
  (fake AsyncNotifiers, geen netwerk): AppBar-titel + sectiekoppen +
  bestaande bronnen renderen, RSS-feed toevoegen/verwijderen roept `save`
  aan, podcast-toggle wijzigt de transcribe-flag, podcast verwijderen
  leegt de lijst.

Niet lokaal gedraaid:
- De runner heeft geen `flutter`/`dart`-binary (zie agent-tip
  pnf-runner-no-flutter), dus `flutter analyze` en `flutter test` zijn niet
  lokaal uitgevoerd; deze worden door CI gevalideerd. Imports zijn handmatig
  opgeschoond zodat analyze geen nieuwe waarschuwingen zou moeten geven.

---

## Review SF-274 (reviewer, 2026-06-25)

[info] Volledige story-diff t.o.v. `main` beoordeeld (6 bestanden, +448/-190).
- Refactor is een zuivere 1-op-1 verhuizing: `_RssFeedsEditor`/`_PodcastFeedsEditor`
  en de `.when()`-loading/error-blokken in `rss_feeds_screen.dart` zijn byte-identiek
  aan de verwijderde code in `settings_screen.dart`. Geen gedragswijziging.
- Settings-pagina: twee secties vervangen door één navigatie-`ListTile`
  (`Icons.rss_feed` + chevron + `MaterialPageRoute`), consistent met API-log/Beheer.
- Uitlog-invalidations voor `rssFeedsProvider`/`podcastFeedsProvider` intact
  (settings_screen.dart:49-50).
- Imports opgeruimd: `url_launcher` weg uit settings (terecht, geen `launchUrl` meer);
  `models.dart` (CategorySettings) en `api_client.dart` (ApiException) nog gebruikt.
- Doc-comment in `data_providers.dart` correct bijgewerkt naar nieuwe locatie; geen
  provider-/backend-/OpenAPI-wijzigingen.
- Widgettests dekken rendering, RSS toevoegen/verwijderen, podcast-toggle en verwijderen.
- Geen JSON-artefacten in story-log/worklog.

[suggestie] De snackbar-/validatiefout-flow van `_PodcastFeedsEditor` (HTTP 400 →
`_extractDutchMessage` → snackbar) en de loading/error-`.when()`-takken zijn niet
door tests gedekt. Pre-existing gedrag, 1-op-1 verhuisd; niet blokkerend.
[info] `flutter analyze`/`flutter test` niet lokaal gedraaid (geen flutter-binary op
runner, bekend); CI valideert. Imports handmatig nagelopen — geen nieuwe warnings verwacht.

Conclusie: voldoet aan alle acceptatiecriteria. Akkoord.

---

## Test SF-275 (tester, 2026-06-25)

Story-brede test op preview `https://pnf-pr-135.vdzonsoftware.nl`
(frontend-build SHA `3bacf50` = HEAD van deze branch).

Verificatie-methode: hard-diff van de verhuisde code + live UI-test via
Playwright op de Flutter-CanvasKit-SPA (coördinaat-flow met wegwerp-account,
opgeruimd via `DELETE /api/account/me` → HTTP 200).

Resultaten per acceptatiecriterium:
- AC1 [ok] Settings toont op de oude RSS/podcast-plek één `ListTile`
  (`Icons.rss_feed` + titel "RSS-feeds" + subtitle "Beheer RSS-feeds en
  podcast-bronnen" + chevron) die naar de subpagina navigeert; inline-editors
  staan niet meer op settings. Bewijs: `10-settings-rss-tile.png`.
- AC2 [ok] Subpagina-sectie "RSS-feeds": feed-URL toevoegen werkt (URL
  verschijnt in monospace met verwijder-`X`); save via `rssFeedsProvider`
  bevestigd live. Bewijs: `11-subpage-top.png`, `13-rss-added.png`.
- AC3 [ok] Subpagina-sectie "Podcast-bronnen" met eigen invoerveld aanwezig.
  Editor-code byte-identiek aan main (validatie/snackbar/toggle/verwijderen
  ongewijzigd). Bewijs: `12-subpage-podcast.png`.
- AC4 [ok] Uitlog-invalidations `rssFeedsProvider`/`podcastFeedsProvider`
  intact (settings_screen.dart:49-50); providers ongewijzigd.
- AC5 [ok] Styling consistent: AppBar "RSS-feeds", sectiekoppen titleLarge,
  dividers, monospace-URL, chevron-tiles — zie screenshots.
- AC6 [info] `flutter analyze` niet uitvoerbaar (geen dart/flutter-binary op
  agent:local-runner). Handmatig geverifieerd: alle imports in
  `settings_screen.dart` en `rss_feeds_screen.dart` zijn in gebruik,
  `url_launcher` terecht weg uit settings, geen achtergebleven editor-refs
  (alleen een doc-comment in data_providers.dart). CI valideert analyze.
- AC7 [ok] Geen backend-/OpenAPI-/providerlogica-wijziging; enige
  data_providers-wijziging is een doc-comment.

Hard-diff bewijs: `_RssFeedsEditor` en `_PodcastFeedsEditor` zijn byte-identiek
verhuisd vanuit `main:settings_screen.dart` naar `rss_feeds_screen.dart`
(`diff` zonder verschillen, op `_VersionBlock`/`_VersionTile` na die terecht in
settings blijven).

Conclusie: alle acceptatiecriteria voldaan (AC6 alleen via code-inspectie i.p.v.
`flutter analyze` wegens ontbrekende toolchain). Test geslaagd.
