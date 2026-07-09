# SF-883 - rename settings to instellingen

## Story

rename settings to instellingen

<!-- refined-by-factory -->

## Scope
In de bottom-navigatiebalk (`frontend/lib/screens/main_shell.dart`) staat het label van het instellingen-tabblad nog in het Engels ("Settings"). Dit moet vervangen worden door de Nederlandse tekst "Instellingen", consistent met de AppBar-titel van het instellingenscherm zelf (`settings_screen.dart`), die al "Instellingen" gebruikt.

Wijziging is beperkt tot het label-attribuut van de `NavigationDestination` voor het instellingen-icoon in `main_shell.dart`. Geen andere schermen, providers of API's zijn betrokken — alle overige voorkomens van "settings" in de codebase zijn technische identifiers (provider-namen, class-namen, API-paden, cache-keys) en vallen buiten scope.

## Acceptance criteria
- Het label van de navigatie-destination voor het instellingen-tabblad in `main_shell.dart` toont "Instellingen" in plaats van "Settings".
- Het icoon en de volgorde van de navigatie-items blijven ongewijzigd.
- De AppBar-titel van het instellingenscherm zelf blijft "Instellingen" (was al correct, geen wijziging nodig).
- Geen functionele wijzigingen aan providers, API-calls of overige teksten.

## Aannames
- "Settings" in `main_shell.dart:32` (het navigatiemenu-label) is het enige zichtbare menu-item dat bedoeld wordt met "In het menu staat 'settings'" uit de issue-omschrijving.
- Overige voorkomens van het woord "settings" in de codebase (provider-namen zoals `settingsProvider`, class `SettingsScreen`, API-pad `/api/settings`, cache-key `'settings'`) zijn technische identifiers, geen UI-tekst, en blijven ongewijzigd.

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summarized"}
