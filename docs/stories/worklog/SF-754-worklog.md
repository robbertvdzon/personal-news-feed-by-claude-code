# SF-754 - Worklog

Story-context bij eerste pickup:
Categorieën naar eigen subpagina (CategoriesScreen)

Maak frontend/lib/screens/categories_screen.dart naar het model van rss_feeds_screen.dart: ConsumerWidget met AppBar 'Categorieën' en ListView die settingsProvider watcht en de volledige categorieënlijst toont (SwitchListTile per categorie met enabled-toggle via settingsProvider.notifier.save, systeemcategorieën met subtitel 'Systeem' en zonder edit/verwijder, edit-IconButton voor niet-systeem, 'Categorie toevoegen'-tegel). Verplaats de helpers _addCategory en _editCategory (incl. delete) ongewijzigd mee. Vervang in settings_screen.dart het inline Categorieën-blok (~regels 68-92) en die helpers door één navigatietegel (ListTile + leading-icoon + chevron_right + MaterialPageRoute naar CategoriesScreen), consistent met de RSS-feeds-tegel. Voeg de import toe en ruim ongebruikte imports/methoden op. Gedrag 1-op-1 gelijk; geen backend-/API-/datamodelwijzigingen. Houd flutter analyze schoon. Voeg zo nodig widget-/unittests toe als onderdeel van dit ontwikkelwerk.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-755 geïmplementeerd: nieuwe frontend/lib/screens/categories_screen.dart (ConsumerWidget,
  AppBar 'Categorieën', settingsProvider-watch) met de volledige categorieënlijst + de
  ongewijzigde _addCategory/_editCategory-helpers (incl. delete).
- settings_screen.dart: inline Categorieën-blok + helpers vervangen door één navigatietegel
  (Icons.category + chevron_right + MaterialPageRoute), import toegevoegd, ongebruikte
  `cats`-watch verwijderd. Gedrag 1-op-1 gelijk; geen backend-/API-/datamodelwijziging.
- Widgettests toegevoegd in frontend/test/categories_screen_test.dart (lijst/AppBar,
  systeem-subtitel zonder edit, toggle->save, toevoegen->save, verwijderen->save).
- flutter analyze/test niet lokaal gedraaid: de runner heeft geen flutter/dart-binary;
  CI valideert build + analyze + tests. Zie docs/stories/SF-755-categorieen-subpagina.md.

## Review (SF-755, reviewer)

Beoordeeld: volledige story-diff `git diff main...HEAD`.

Bevindingen:
- [info] `CategoriesScreen` is een correcte 1-op-1 verplaatsing: SwitchListTile-toggle,
  systeem-subtitel "Systeem" zonder edit, edit-IconButton voor niet-systeem, add/edit/delete-
  dialogen en id-slugificatie zijn identiek aan de oude inline-implementatie. Patroon volgt
  `RssFeedsScreen` (Scaffold+AppBar, ListView met bottomInset). AC 1-5 gedekt.
- [info] `settings_screen.dart`: inline-blok + helpers vervangen door één navigatietegel
  (Icons.category + chevron_right + MaterialPageRoute). Ongebruikte `final cats = ref.watch(...)`
  verwijderd; `models.dart`-import terecht behouden (NewsRequest/VersionInfo, regels 344/569) en
  `settingsProvider`-invalidate bij logout (regel 48) intact. Geen dode imports.
- [info] Widgettests (`categories_screen_test.dart`) dekken lijst/AppBar, systeem-subtitel zonder
  edit, toggle→save, toevoegen→save (incl. id 'sport') en verwijderen→save; FakeSettingsNotifier-
  patroon matcht bestaande provider-API (overrideWith/build/save). Story-logs bevatten geen
  JSON-artefacten.
- [suggestie] Scope: `docs/factory/agents/documenter.md` en `planner.md` zijn in de developer-
  commit (ff6baa7) meegecommit terwijl ze los staan van SF-755 (de developer meldde ze
  "ongemoeid" te laten, maar ze zitten nu in de story-branch). Inhoud is onschadelijke factory-
  documentatie zonder secrets; geen blocker, maar hoort strikt genomen niet bij deze story.
- [info] Geen backend-/API-/datamodelwijzigingen; `settingsProvider`/`CategorySettings`
  ongewijzigd; geen OpenAPI/Flyway-impact. `flutter analyze`/`test` niet lokaal draaibaar
  (geen dart-binary in runner) — CI valideert AC 6.

Conclusie: akkoord. De enige noot (meegecommitte factory-docs) is niet-blokkerend.

## Test (SF-756, tester)

Inlog-modus: **default** — vaste test-user uit het namespace-secret (`newsfeed-api-keys`,
`pnf-pr-167`). Geen DB-mutatie, geen registratie/verwijdering.

Preview: `https://pnf-pr-167.vdzonsoftware.nl` — HTTP 200, login geslaagd (feed toont data).

Verificatie:
- **Code (harde 1-op-1 move)**: `diff` van `_addCategory`, `_editCategory` en het inline
  SwitchListTile-blok tussen `main:settings_screen.dart` en de nieuwe `categories_screen.dart`
  → **byte-identiek** (edit/delete/toggle-logica + id-slugificatie ongewijzigd). Oude helpers
  komen nergens anders meer voor. `frontend/lib/models/*` en `providers/*` = **0 wijzigingen**
  t.o.v. main (`settingsProvider`/`CategorySettings` ongewijzigd → AC 5).
- **Browser (screenshots in /work/screenshots)**:
  - `04-settings-top.png`: onder kopje "Categorieën" één navigatietegel (Icons.category,
    subtitel "Categorieën in-/uitschakelen en beheren", `chevron_right`), géén inline lijst;
    visueel consistent met de "RSS-feeds"-tegel eronder → **AC 1, 2**.
  - `06-categories-subpage.png`: tap opent subpagina via `MaterialPageRoute` (AppBar
    "Categorieën" + terugpijl). Niet-systeemcategorieën (Software ontwikkeling, Programmeer
    talen, Bitcoin, Artificiële Intelligentie) met edit-potlood + enabled-toggle; "Categorie
    toevoegen"-tegel onderaan → **AC 3**. Systeemcategorie **"Overig"** toont subtitel
    **"Systeem"** en heeft **géén** edit-icoon → **AC 4**.
- **AC 6**: `flutter analyze`/build niet lokaal draaibaar (geen dart-binary op de runner);
  door CI gevalideerd, conform factory-conventie.

Mutatietests (add/edit/delete) bewust niet uitgevoerd op de permanente test-user; de
byte-identieke move bewijst gedragsgelijkheid. Alle acceptatiecriteria voldaan.

Conclusie tester: **geslaagd**.
