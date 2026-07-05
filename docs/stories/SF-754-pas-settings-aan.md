# SF-754 - pas settings aan

## Story

pas settings aan

<!-- refined-by-factory -->

## Scope
Verplaats het beheer van categorieën in het instellingenscherm van de Flutter-app (`frontend/lib/screens/settings_screen.dart`) naar een eigen subpagina, volgens hetzelfde patroon als de RSS-feeds (`RssFeedsScreen`, bereikt via SF-220/SF-312).

Concreet:
- Er komt een nieuwe subpagina (bijv. `CategoriesScreen`) die de volledige categorieënlijst en het bijbehorende beheer bevat: aan/uit-schakelen (`enabled`), toevoegen, bewerken (naam + extra instructies) en verwijderen, inclusief de bestaande behandeling van systeemcategorieën (geen edit/verwijder-knop, subtitel "Systeem").
- Op het hoofd-instellingenscherm vervangt de nu inline uitgeklapte "Categorieën"-sectie door één navigatietegel (`ListTile` met leading-icoon, titel "Categorieën", `chevron_right`, `MaterialPageRoute`), visueel consistent met de bestaande "RSS-feeds"-tegel.
- Alleen frontend-herschikking; geen backend-, API- of datamodelwijzigingen. `settingsProvider` en `CategorySettings` blijven ongewijzigd.

Buiten scope: wijzigingen aan RSS-/podcast-beheer, andere instellingssecties, of het gedrag/de validatie van categorieën zelf.

## Acceptance criteria
1. Op het instellingenscherm staat onder een eigen kopje/tegel "Categorieën" een navigeerbare tegel (met `chevron_right`) die naar een aparte subpagina leidt; de volledige categorieënlijst is niet meer inline op het hoofdscherm zichtbaar.
2. De categorie-tegel volgt hetzelfde interactiepatroon als de bestaande "RSS-feeds"-tegel (tappen opent de subpagina via `MaterialPageRoute`).
3. Op de subpagina kan de gebruiker: categorieën in-/uitschakelen, een categorie toevoegen, een niet-systeemcategorie bewerken (naam + extra instructies) en verwijderen — met exact hetzelfde gedrag als voorheen.
4. Systeemcategorieën tonen op de subpagina de subtitel "Systeem" en hebben geen bewerk-/verwijderoptie, net als in de huidige implementatie.
5. Wijzigingen worden opgeslagen via `settingsProvider.notifier.save(...)` en zijn na herladen zichtbaar; er zijn geen backend- of API-contractwijzigingen.
6. De Flutter-app bouwt en `flutter analyze` levert geen nieuwe waarschuwingen/fouten op voor de gewijzigde bestanden.

## Aannames
- "Onder een eigen kopje, net als de rss-url's" wordt geïnterpreteerd als: dezelfde subpagina-aanpak als de RSS-feeds (aparte pagina + navigatietegel), niet slechts een tekstkop op het hoofdscherm — dit sluit aan op het bestaande, door de gebruiker gewenste patroon uit SF-220/SF-312.
- De wijziging betreft uitsluitend `frontend/` (de hoofd-app); `frontend-reader/` heeft geen instellingenscherm en valt buiten scope.
- Bestaande dialoog-teksten, iconen en validatielogica voor categorieën blijven ongewijzigd; alleen de plaatsing verandert.
- Geen bevestigingsdialoog of extra UI-elementen toevoegen die er nu niet zijn.
- `docs/factory/` is reeds volledig ingevuld (SF-220), dus er hoeft geen docs-aanvulling-acceptatiecriterium te worden toegevoegd.

## Eindsamenvatting

Ik heb voldoende context. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-754: Categorieën naar eigen subpagina

**Doel**
Het beheer van categorieën in het instellingenscherm van de Flutter-app verplaatsen naar een eigen subpagina, volgens hetzelfde patroon als de RSS-feeds. Puur een frontend-herschikking — geen backend-, API- of datamodelwijzigingen.

**Wat is gebouwd**
- **Nieuwe subpagina** `frontend/lib/screens/categories_screen.dart` (`CategoriesScreen`, `ConsumerWidget`, naar model van `RssFeedsScreen`): eigen Scaffold/AppBar "Categorieën" met de volledige categorieënlijst en alle beheer — aan/uit-schakelen, toevoegen, bewerken (naam + extra instructies) en verwijderen. Systeemcategorieën tonen subtitel "Systeem" en hebben geen edit/verwijder-knop.
- **`settings_screen.dart`**: de inline uitgeklapte categorie-sectie vervangen door één navigatietegel (`ListTile` met `Icons.category` + `chevron_right` + `MaterialPageRoute`), visueel consistent met de bestaande RSS-feeds-tegel. Ongebruikt geworden `cats`-watch en de helpers `_addCategory`/`_editCategory` verwijderd; `settingsProvider`-invalidate bij logout en de `models.dart`-import blijven behouden.
- **Widgettests** `frontend/test/categories_screen_test.dart` (5 tests, fake-notifier-patroon): lijst + AppBar-titel, systeemcategorie (subtitel + geen edit), toggle→save, toevoegen→save, verwijderen→save.

**Gemaakte keuzes**
- De categorielogica (toggle, `_addCategory`, `_editCategory` incl. verwijderen, systeemcategorie-afhandeling) is **1-op-1, byte-identiek** verplaatst — teksten, iconen en validatie ongewijzigd, dus geen gedragsverandering.
- "Onder een eigen kopje, net als de rss-url's" is bewust geïnterpreteerd als een aparte subpagina + navigatietegel (zoals SF-220/SF-312), niet slechts een tekstkop op het hoofdscherm.
- `settingsProvider` en `CategorySettings` blijven ongewijzigd.

**Getest**
- **Statisch**: diff bevestigt byte-identieke move; geen dangling references; nav-tegel volgt exact het RSS-feeds-patroon; diff raakt uitsluitend `frontend/` + factory-docs.
- **Preview (Playwright, `pnf-pr-168`)**: nav-tegel op settings zonder inline lijst (AC1/AC2); subpagina met toggles, edit-dialoog, "Categorie toevoegen", systeemcategorie "Overig" met subtitel "Systeem" en zonder edit/verwijder (AC3/AC4); toggle blijft na herladen persistent → save-round-trip via `settingsProvider.notifier.save(...)` bevestigd (AC5). Per ongeluk gewijzigde testdata is hersteld en geverifieerd.
- **Conclusie tester**: alle acceptatiecriteria AC1–AC6 voldaan, geen bugs of regressies.

**Bewust niet gedaan / aandachtspunten**
- Geen backend-, API- of datamodelwijzigingen; geen bevestigingsdialogen of extra UI toegevoegd.
- **AC6 (build/`flutter analyze`)** is niet lokaal gedraaid: op de developer- en tester-runners ontbreekt de flutter/dart-binary. Validatie leunt op CI (frontend-image + APK-build bouwen de Flutter-app) en het feit dat de preview op deze branch draait. Definitieve bevestiging komt uit CI.
- Kleine noot uit review: `docs/factory/agents/documenter.md` en `planner.md` zijn meegecommit terwijl ze strikt buiten de frontend-scope vallen (waren al untracked, meegesleept door een blanket-commit). Legitieme factory-docs, niet blokkerend.

---
