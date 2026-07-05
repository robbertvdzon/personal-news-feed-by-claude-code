# SF-755 — Categorieën naar eigen subpagina (CategoriesScreen)

Parent story: SF-754 (pas settings aan).

Verplaats het beheer van categorieën uit `settings_screen.dart` naar een eigen
subpagina (`CategoriesScreen`), volgens hetzelfde patroon als de RSS-feeds
(`RssFeedsScreen`, SF-220/SF-312). Alleen frontend-herschikking; geen backend-,
API- of datamodelwijzigingen.

## Stappenplan

- [x] Issue + factory-docs lezen (`.task.md`, `settings_screen.dart`, `rss_feeds_screen.dart`)
- [x] `frontend/lib/screens/categories_screen.dart` aanmaken naar model van `rss_feeds_screen.dart`
- [x] Inline Categorieën-blok + helpers in `settings_screen.dart` vervangen door navigatietegel
- [x] Ongebruikte code opruimen (`cats`-watch, `_addCategory`/`_editCategory`), import toevoegen
- [x] Widgettests schrijven (`test/categories_screen_test.dart`)
- [x] Story-log + worklog bijwerken
- [ ] `flutter analyze` / `flutter test` — door CI gevalideerd (geen flutter/dart op de runner)

## Gedaan / waarom

- **`CategoriesScreen`** is een `ConsumerWidget` met `Scaffold` + `AppBar('Categorieën')`
  en een `ListView` die `settingsProvider` watcht. De volledige lijst (SwitchListTile
  per categorie met `enabled`-toggle via `settingsProvider.notifier.save`,
  systeemcategorieën met subtitel "Systeem" en zonder edit, edit-`IconButton` voor
  niet-systeem, "Categorie toevoegen"-tegel) en de helpers `_addCategory`/`_editCategory`
  (incl. delete) zijn 1-op-1 meeverhuisd — gedrag ongewijzigd.
- **`settings_screen.dart`**: het inline uitgeklapte Categorieën-blok en de twee helpers
  zijn vervangen door één navigatietegel (`ListTile` + `Icons.category` + `chevron_right` +
  `MaterialPageRoute` naar `CategoriesScreen`), visueel/consistent met de RSS-feeds-tegel.
  De nu ongebruikte `final cats = ref.watch(settingsProvider)` is verwijderd; de
  `settingsProvider`-invalidatie bij uitloggen en de `models.dart`-import (nog gebruikt door
  o.a. `NewsRequest`/`VersionInfo`) blijven staan.
- **Tests**: `test/categories_screen_test.dart` dekt AppBar-titel + lijst, systeem-subtitel
  zonder edit-knop, enabled-toggle → save, toevoegen → save (incl. id-slugificatie) en
  verwijderen → save met lege lijst. Zelfde fake-notifier-aanpak als `rss_feeds_screen_test.dart`.

## Niet lokaal verifieerbaar

De factory-developer-runner heeft geen flutter/dart-binary, dus `flutter analyze` en
`flutter test` konden niet lokaal draaien; CI valideert build + analyze + tests (AC 6).
