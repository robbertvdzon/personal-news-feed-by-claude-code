# SF-220 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

## Eindsamenvatting — SF-220: RSS-feeds en podcast-RSS naar eigen subpagina van Settings

**Doel**
De te lange settings-pagina ontlasten door het beheer van RSS-feeds én podcast-RSS-bronnen te verplaatsen naar één aparte subpagina. Puur frontend-herstructurering — geen backend-, API- of providerwijzigingen.

**Wat is gebouwd**
- **Nieuwe subpagina** `frontend/lib/screens/rss_feeds_screen.dart` (`RssFeedsScreen`, eigen `Scaffold` + `AppBar` "RSS-feeds"). De secties "RSS-feeds" (`_RssFeedsEditor`, op `rssFeedsProvider`) en "Podcast-bronnen" (`_PodcastFeedsEditor`, KAN-56, op `podcastFeedsProvider`) zijn 1-op-1 hierheen verhuisd — inclusief sectiekoppen, `.when()`-loading/error ("Fout: …") en de editor-klassen.
- **`settings_screen.dart`**: beide inline-editors vervangen door één navigatie-`ListTile` (`Icons.rss_feed`, titel "RSS-feeds", subtitle "Beheer RSS-feeds en podcast-bronnen", chevron + `MaterialPageRoute`), consistent met de bestaande API-log/Beheer-tiles.
- Opgeruimd: ongebruikt geworden `ref.watch(rssFeedsProvider/podcastFeedsProvider)` en de `url_launcher`-import. De uitlog-`invalidate`-aanroepen voor beide providers blijven intact.
- Stale doc-comment in `data_providers.dart` bijgewerkt naar de nieuwe editor-locatie.

**Gemaakte keuzes**
- Beide editors op één gedeelde subpagina (één knop), zoals de description aangaf — niet twee aparte pagina's.
- Gedrag, styling, synchrone URL-validatie + snackbar, transcribe-toggle en monospace-URL-weergave exact ongewijzigd overgenomen (byte-identieke verhuizing).
- Geen named routes/deeplinks; bestaand `MaterialPageRoute`-patroon gevolgd.

**Wat is getest**
- **Widgettests** (`frontend/test/rss_feeds_screen_test.dart`) met fake AsyncNotifiers: rendering van AppBar + sectiekoppen + bestaande bronnen, RSS-feed toevoegen/verwijderen roept `save`, podcast-toggle wijzigt transcribe-flag, podcast verwijderen leegt de lijst.
- **Reviewer (SF-274)**: akkoord — zuivere 1-op-1 refactor, alle acceptatiecriteria voldaan, invalidations en imports correct.
- **Tester (SF-275)**: story-brede live UI-test op preview `pnf-pr-135` (Playwright, wegwerp-account opgeruimd). Alle 7 acceptatiecriteria voldaan; hard-diff bevestigt byte-identieke verhuizing van beide editors.

**Bewust/niet gedaan**
- `flutter analyze` en `flutter test` zijn **niet lokaal** gedraaid: de factory-runner heeft geen flutter/dart-toolchain. Imports zijn handmatig opgeschoond; CI valideert analyze/tests. AC6 is daardoor via code-inspectie geverifieerd i.p.v. een lokale analyze-run.
- De pre-existing snackbar-/validatiefout-flow en de loading/error-takken van de podcast-editor zijn niet door tests gedekt (ongewijzigd, 1-op-1 verhuisd) — door reviewer als niet-blokkerend gemarkeerd.
- Geen wijzigingen aan backend, OpenAPI-spec of providerlogica (enige `data_providers.dart`-wijziging is een doc-comment).

**Status**: development → review-approved → test-approved. Klaar voor documentatie (SF-277), handmatige goedkeuring en merge.
