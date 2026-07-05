# SF-754 - Worklog

Story-context bij eerste pickup:
CategoriesScreen als subpagina + navigatietegel op settings

Maak nieuwe subpagina frontend/lib/screens/categories_screen.dart (ConsumerWidget naar model van RssFeedsScreen) met eigen Scaffold/AppBar 'Categorieën' en de volledige categorieënlijst: aan/uit (SwitchListTile op settingsProvider), toevoegen (_addCategory), bewerken (_editCategory: naam + extra instructies) en verwijderen, inclusief systeemcategorie-afhandeling (subtitel 'Systeem', geen edit/verwijder). Vervang op settings_screen.dart de inline categorie-sectie (regels ~68-92) door één navigatietegel (ListTile + leading-icoon + chevron_right + MaterialPageRoute), consistent met de RSS-feeds-tegel; voeg de import toe en verwijder de nu ongebruikte cats-watch en _addCategory/_editCategory zodat flutter analyze schoon blijft. Geen backend-/API-/datamodelwijzigingen; teksten, iconen en validatie ongewijzigd. Zorg dat flutter analyze geen nieuwe waarschuwingen geeft.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuwe subpagina `frontend/lib/screens/categories_screen.dart` toegevoegd (SF-762):
  `CategoriesScreen extends ConsumerWidget` met eigen Scaffold/AppBar 'Categorieën',
  volgens hetzelfde patroon als `RssFeedsScreen`. De volledige categorieënlijst
  (SwitchListTile op `settingsProvider`), de `_addCategory`- en `_editCategory`-dialogen
  (inclusief verwijderen) en de systeemcategorie-afhandeling (subtitel 'Systeem',
  geen edit/verwijder-knop) zijn 1-op-1 verplaatst — teksten, iconen en validatie
  ongewijzigd.
- `frontend/lib/screens/settings_screen.dart`:
  - De inline uitgeklapte categorie-sectie vervangen door één navigatietegel onder
    het kopje 'Categorieën' (`ListTile` met `Icons.category` + `chevron_right` +
    `MaterialPageRoute`), visueel consistent met de bestaande RSS-feeds-tegel.
  - Import `categories_screen.dart` toegevoegd.
  - De nu ongebruikt geworden `final cats = ref.watch(settingsProvider);` verwijderd.
  - De ongebruikt geworden helpers `_addCategory` en `_editCategory` verwijderd.
  - `settingsProvider`-invalidate bij logout en de `models.dart`-import (nog gebruikt
    door NewsRequest/VersionInfo) blijven behouden.
- Widgettest `frontend/test/categories_screen_test.dart` toegevoegd (5 tests) naar
  het bestaande fake-notifier-patroon (FakeSettingsNotifier extends SettingsNotifier,
  override build()/save(), inject via settingsProvider.overrideWith): AppBar-titel +
  lijst, systeemcategorie-subtitel + geen edit-knop, toggle → save, toevoegen → save,
  verwijderen → save.

Test/validatie:
- De factory developer-runner heeft geen flutter/dart-binary (`which flutter dart`
  faalt); `flutter analyze`/`flutter test` kunnen niet lokaal draaien. Code is
  handmatig geverifieerd op dangling references (geen resterende `CategorySettings`/
  `_addCategory`/`_editCategory`-verwijzingen in settings_screen; `settingsProvider`
  en `models.dart`-import nog gebruikt en behouden). Analyze + tests worden door CI
  gevalideerd (AC6).
- Geen backend-, API- of datamodelwijzigingen; `settingsProvider` en `CategorySettings`
  ongewijzigd.
