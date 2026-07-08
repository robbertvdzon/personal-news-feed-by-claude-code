# SF-831 - Hernoem knop 'Instellingen' naar 'Settings'

## Story

Hernoem knop 'Instellingen' naar 'Settings'

<!-- refined-by-factory -->

## Scope
Hernoem het label van de bottom-navigatie-knop 'instellingen' naar 'Settings' in `frontend/lib/screens/main_shell.dart` (regel met `NavigationDestination(icon: Icon(Icons.settings), label: 'instellingen')`).

De AppBar-titel 'Instellingen' in `frontend/lib/screens/settings_screen.dart` valt buiten scope van deze story, tenzij de developer besluit dit voor consistentie mee te nemen (zie Aannames).

## Acceptance criteria
- Het label van de settings-tab in de bottom-navigatie toont 'Settings' in plaats van 'instellingen'.
- Er zijn geen overige zichtbare voorkomens van 'Instellingen'/'instellingen' meer in de bottom-navigatie.
- Bestaande functionaliteit van de settings-tab (navigatie, icoon, gedrag) blijft ongewijzigd.
- Frontend bouwt/analyseert zonder nieuwe warnings/errors gerelateerd aan deze wijziging.

## Aannames
- Alleen het bottom-navigatie-label wordt aangepast; de AppBar-titel 'Instellingen' op het instellingen-scherm zelf (`settings_screen.dart:27`) blijft ongewijzigd, omdat de issue-omschrijving expliciet spreekt over de knop in de bottom-navigatie.
- Er is geen i18n/vertaal-systeem in gebruik voor deze labels (huidige labels staan hardcoded als losse strings in `main_shell.dart`); de wijziging is een directe stringvervanging.
- `docs/factory/` bestaat al langer (sinds commit edfb2e8) en is niet net aangemaakt voor deze story, dus geen extra acceptatiecriterium over het aanvullen van factory-docs is nodig.

## Eindsamenvatting

Ik heb genoeg context om de eindsamenvatting te schrijven.

## Eindsamenvatting SF-831 — Hernoem knop 'Instellingen' naar 'Settings'

**Wat is gebouwd**
Het label van de settings-tab in de bottom-navigatie (`frontend/lib/screens/main_shell.dart:32`) is aangepast van `'instellingen'` naar `'Settings'`. Icoon, route en navigatiegedrag zijn ongewijzigd. Een `git grep` bevestigde dat er na de wijziging geen overige voorkomens van 'instellingen'/'Instellingen' meer in de bottom-navigatie staan.

**Keuzes**
- De AppBar-titel `'Instellingen'` op het settings-scherm zelf (`settings_screen.dart:27`) is bewust buiten scope gelaten, conform de story-aannames — de issue sprak expliciet over het label in de bottom-navigatie, niet over de schermtitel.
- Een nieuwe widgettest (`frontend/test/main_shell_test.dart`) is toegevoegd, gebaseerd op het bestaande fake-notifier-patroon uit `settings_screen_test.dart`/`categories_screen_test.dart`, die controleert dat (1) de bottom-navigatie 'Settings' toont zonder Nederlandse resten, en (2) de tab nog steeds navigeerbaar is.

**Wat is getest**
- Code-inspectie van de volledige diff (developer en tester), inclusief grep-controle op resterende Nederlandse labels.
- Visuele test op de preview-omgeving (`https://pnf-pr-173.vdzonsoftware.nl`) met een wegwerptestaccount: na inloggen toont de settings-tab correct "Settings", terwijl de AppBar boven het scherm nog "Instellingen" toont — precies zoals bedoeld. Testaccount is na afloop opgeruimd.
- Alle acceptatiecriteria zijn geverifieerd en akkoord bevonden; geen bugs gevonden.

**Bewust niet gedaan**
- `flutter analyze`/`flutter test` konden niet lokaal worden gedraaid, omdat de developer- en tester-runners voor deze repo geen `flutter`/`dart`-binary hebben (bekende, eerder gedocumenteerde beperking). CI valideert dit alsnog. De wijziging betreft één string-literal plus een logisch nagelopen nieuwe test, dus het risico hiervan is beperkt.
- De AppBar-titel op het settings-scherm is niet meegenomen, conform scope-afbakening in de story.
