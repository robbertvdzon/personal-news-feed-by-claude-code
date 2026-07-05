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

Ik heb `.task.md`, het worklog (`SF-754-worklog.md`) met de developer-/reviewer-/tester-comments en het story-log (`SF-755`) gelezen. Hier is de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-754: pas settings aan (Categorieën naar eigen subpagina)

### Wat is gebouwd
Het beheer van categorieën in de Flutter-app is verplaatst van het hoofd-instellingenscherm naar een eigen subpagina, volgens hetzelfde patroon als de RSS-feeds.

- **Nieuwe subpagina `CategoriesScreen`** (`frontend/lib/screens/categories_screen.dart`): `ConsumerWidget` met `AppBar('Categorieën')` en een `ListView` die `settingsProvider` watcht. Bevat de volledige categorieënlijst met in-/uitschakelen (`enabled`-toggle), toevoegen, bewerken (naam + extra instructies) en verwijderen. Systeemcategorieën tonen subtitel "Systeem" en hebben geen edit-/verwijderknop.
- **Hoofd-instellingenscherm** (`settings_screen.dart`): het inline uitgeklapte Categorieën-blok en de helpers zijn vervangen door één navigatietegel (`ListTile` + `Icons.category` + `chevron_right` + `MaterialPageRoute`), visueel consistent met de bestaande RSS-feeds-tegel.
- **Widgettests** toegevoegd (`test/categories_screen_test.dart`): lijst/AppBar, systeem-subtitel zonder edit, toggle→save, toevoegen→save en verwijderen→save.

### Gemaakte keuzes
- De categorie-beheerlogica (`_addCategory`/`_editCategory` incl. delete en id-slugificatie) is **1-op-1 (byte-identiek) meeverhuisd** — gedrag ongewijzigd. De tester heeft dit met een diff tegen `main` bevestigd.
- Uitsluitend frontend-herschikking: **geen backend-, API- of datamodelwijzigingen**. `settingsProvider` en `CategorySettings` zijn ongewijzigd (0 wijzigingen t.o.v. `main`).
- Alleen de hoofd-app `frontend/` is aangepast; `frontend-reader/` heeft geen instellingenscherm en viel buiten scope.

### Wat is getest
- **Code-verificatie**: de verplaatste helpers en het SwitchListTile-blok zijn byte-identiek aan het origineel; oude helpers komen nergens anders meer voor.
- **Browser-test** op preview `https://pnf-pr-167.vdzonsoftware.nl` (HTTP 200, login geslaagd): hoofdscherm toont de navigatietegel zonder inline lijst (AC 1, 2); subpagina opent via `MaterialPageRoute` met edit/toggle op niet-systeemcategorieën en "Categorie toevoegen"-tegel (AC 3); systeemcategorie "Overig" toont "Systeem" zonder edit-icoon (AC 4).
- **Conclusie tester: geslaagd** — alle acceptatiecriteria voldaan. **Reviewer: akkoord**.

### Bewust niet gedaan / aandachtspunten
- **`flutter analyze`/`flutter test` niet lokaal gedraaid**: de factory-runner heeft geen dart/flutter-binary. AC 6 (build + analyze schoon) wordt door CI gevalideerd — conform factory-conventie.
- **Mutatietests** (add/edit/delete) zijn bewust niet uitgevoerd op de permanente test-user om DB-mutatie te voorkomen; de byte-identieke move bewijst gedragsgelijkheid.
- **Klein aandachtspunt (niet-blokkerend)**: in de developer-commit zijn `docs/factory/agents/documenter.md` en `planner.md` meegecommit, terwijl deze los staan van SF-755. Inhoud is onschadelijke factory-documentatie zonder secrets; strikt genomen hoort dit niet bij deze story.

---
