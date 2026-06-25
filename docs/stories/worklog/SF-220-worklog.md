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
