# SF-1850 - Worklog

Story-context bij eerste pickup:
Categorieënscherm: faalcontract gelijktrekken met de RSS-feeds-editor

Frontend-only. 1) frontend/lib/providers/data_providers.dart - SettingsNotifier.save: verwijder de optimistische `state = AsyncData(categories)` (:177) zodat de volgorde PUT /api/settings → LocalCache.saveList → `state` uit de serverrespons wordt (die slotregel bestaat al en blijft ongewijzigd). Laat de ApiException naar de caller propageren en voeg een WHY-comment toe in dezelfde stijl/strekking als bij RssFeedsNotifier.save (:197-201) en PodcastFeedsNotifier.save (:232-234). 2) frontend/lib/screens/categories_screen.dart - zet CategoriesScreen om naar ConsumerStatefulWidget/ConsumerState (nodig voor een _busy-vlag); _addCategory en _editCategory worden state-methodes zonder context/ref-parameters. Voeg één `Future<void> _save(List<CategorySettings> next)` toe naar het model van _RssFeedsEditorState._save (rss_feeds_screen.dart:102-124): setState(_busy = true) → `await ref.read(settingsProvider.notifier).save(next)` in een try → in catch een mounted-check plus een snackbar met `backgroundColor: Theme.of(context).colorScheme.error` → finally _busy terug achter een mounted-check. Meldingstekst: bij ApiException met statuscode 400 de gedeelde message-extractie, anders een generieke Nederlandse fallback (bv. 'Fout bij opslaan: $e'). GEEN validateFailureMessage-parameter (geen invoerveld dat geleegd moet worden; backend valideert categorieën niet). Laat alle vier de huidige save-aanroepen via _save lopen: schakelaar (:31, nu zónder await), toevoegen (:66), hernoemen/extra instructies (:95) en verwijderen (:98). Tijdens _busy zijn de SwitchListTiles (onChanged: null), het bewerk-icoon en de 'Categorie toevoegen'-tile uitgeschakeld, in dezelfde stijl als de RSS-editor; let op context-gebruik na await (altijd mounted-check) en op de eigen ctx van de showDialog-dialogen. 3) Verplaats _extractDutchMessage (rss_feeds_screen.dart:131-139) ongewijzigd van gedrag naar frontend/lib/api/api_client.dart als publieke top-level functie `extractDutchMessage(String body, {required String emptyFallback})`, inclusief de bestaande doc-comment; laat rss_feeds_screen.dart de nieuwe locatie importeren. LET OP: de helper heeft daar TWEE callers (:112 RSS-editor en :242 podcast-editor) - beide moeten functioneel identiek blijven. 4) Werk de verwijzing in docs/factory/technical-spec.md (~regel 74, codeconventie over {"error": "…"}-foutbodies) bij naar de nieuwe locatie; die noemt nu letterlijk frontend/lib/screens/rss_feeds_screen.dart. 5) Werk specs/frontend-spec.md §9b (Categorieën-subpagina, ~regel 384-400) bij: beschrijf per actie (schakelaar, toevoegen, bewerken/opslaan, verwijderen) dat de lijst pas muteert ná een geslaagde PUT, dat de bediening tijdens het opslaan uitgeschakeld is en dat er bij een fout een rode snackbar met de Nederlandse melding uit het error-veld verschijnt - in dezelfde formuleringsstijl als het RSS-blok in §9a; de zin 'gedrag ongewijzigd t.o.v. de inline-sectie' klopt dan niet meer. 6) Breid frontend/test/categories_screen_test.dart uit met minimaal één faalpad-widgettest naar het model van frontend/test/rss_feeds_screen_test.dart: fake-notifier die ApiException(400, '{"error":"..."}') gooit; assert dat na een omschakel-actie de lijst-state ongewijzigd blijft en de snackbar de servermelding toont (niet de rauwe JSON). De vijf bestaande tests met de altijd-slagende fake moeten groen blijven (mogelijk extra pumps nodig doordat de state nu pas na de save muteert). Buiten scope: newsfeedbackend/ (SettingsServiceImpl.saveCategories blijft ongewijzigd), specs/openapi.yaml, frontend-reader/, het RSS-/podcastgedrag zelf en de overige optimistische notifiers in data_providers.dart (setFeedback, item-delete). Sluit af met een zelf-review en draai `cd frontend && flutter test` plus `flutter analyze` (geen nieuwe waarschuwingen); werk docs/stories/worklog/SF-1850-worklog.md bij met wat er is gedaan.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1851 — developer-run (2026-08-03)

Gedaan:
1. `frontend/lib/api/api_client.dart` — `_extractDutchMessage` uit
   `rss_feeds_screen.dart` verplaatst naar een publieke top-level functie
   `extractDutchMessage(String body, {required String emptyFallback})`,
   gedrag ongewijzigd (zelfde regex op het `error`-veld, zelfde fallbacks).
   Beide bestaande callers in `rss_feeds_screen.dart` (RSS-editor en
   podcast-editor) gebruiken nu de nieuwe locatie; `api_client.dart` was daar
   al geïmporteerd, dus geen extra import nodig.
2. `frontend/lib/providers/data_providers.dart` — `SettingsNotifier.save`:
   de optimistische `state = AsyncData(categories)` verwijderd. Volgorde is nu
   PUT `/api/settings` → `LocalCache.saveList` → `state` uit de serverrespons,
   identiek aan `RssFeedsNotifier.save`/`PodcastFeedsNotifier.save`, met een
   WHY-comment in dezelfde stijl. De `ApiException` propageert naar de caller.
3. `frontend/lib/screens/categories_screen.dart` — omgezet naar
   `ConsumerStatefulWidget`/`ConsumerState` met een `_busy`-vlag. Nieuwe
   `Future<void> _save(List<CategorySettings> next)` naar het model van
   `_RssFeedsEditorState._save`: `setState(_busy = true)` → `await save(next)`
   in een try → catch met mounted-check en een rode snackbar
   (`Theme.of(context).colorScheme.error`) → finally `_busy` terug achter een
   mounted-check. Melding: bij `ApiException` met status 400 de gedeelde
   `extractDutchMessage`, anders `'Fout bij opslaan: $e'`. Geen
   `validateFailureMessage`-parameter (geen invoerveld dat geleegd moet worden).
   Alle vier de aanroepen lopen nu via `_save`, inclusief de schakelaar (die
   eerder zónder await opsloeg). Tijdens `_busy`: `SwitchListTile.onChanged`
   null, bewerk-icoon uitgeschakeld en de "Categorie toevoegen"-tile
   uitgeschakeld. `_addCategory`/`_editCategory` zijn state-methodes zonder
   context/ref-parameters; de dialogen houden hun eigen `ctx`.
4. `docs/factory/technical-spec.md` — verwijzing naar `_extractDutchMessage` in
   `rss_feeds_screen.dart` bijgewerkt naar de gedeelde `extractDutchMessage` in
   `frontend/lib/api/api_client.dart`; in de notifier-conventie is
   `SettingsNotifier.save` aan de voorbeeldenlijst toegevoegd.
5. `specs/frontend-spec.md` §9b — de zin "de lijst, dialogen en hun gedrag zijn
   ongewijzigd t.o.v. de oude inline-sectie" vervangen door een beschrijving van
   het nieuwe faalcontract (pas muteren na een geslaagde PUT, bediening
   uitgeschakeld tijdens opslaan, rode snackbar met de melding uit het
   `error`-veld), plus per actie de faalconsequentie — in dezelfde stijl als §9a.
6. `frontend/test/categories_screen_test.dart` — drie tests toegevoegd:
   `_FailingSettingsNotifier` (gooit `ApiException(400, '{"error":"..."}')`) voor
   het schakelaar- en het verwijder-pad (lijst/schakelaarstand ongewijzigd,
   snackbar toont de servertekst en niet de rauwe JSON), en
   `_SlowSettingsNotifier` voor de busy-state (schakelaar, bewerk-icoon en
   "Categorie toevoegen" uitgeschakeld tijdens het opslaan). De vijf bestaande
   tests bleven ongewijzigd groen.

Verificatie:
- `cd frontend && flutter test`: 25 tests groen ("All tests passed!").
- `flutter analyze`: 7 issues, allemaal pre-existing (ws_client, feed_screen,
  podcast_detail_screen, rss_detail_screen, rss_screen) — geen nieuwe
  waarschuwingen; `categories_screen.dart` staat er niet tussen.
- Vangnet `mvn -B --no-transfer-progress clean verify` in
  `newsfeedbackend/newsfeedbackend`: BUILD SUCCESS, exit 0, 61 e2e-tests +
  unit-tests, 0 failures/errors (~3:09 min). Backend is niet gewijzigd.
- `frontend/pubspec.lock` is niet gedirtied door deze run.

Niet gedaan (bewust buiten scope): `newsfeedbackend/`
(`SettingsServiceImpl.saveCategories` blijft ongewijzigd), `specs/openapi.yaml`,
`frontend-reader/`, het RSS-/podcastgedrag zelf en de overige optimistische
notifiers in `data_providers.dart` (`setFeedback`, item-`delete`).
