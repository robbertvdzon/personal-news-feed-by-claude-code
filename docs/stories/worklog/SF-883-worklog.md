# SF-883 - Worklog

Story-context bij eerste pickup:
Label instellingen-tabblad naar Nederlands

Wijzig in frontend/lib/screens/main_shell.dart de label van de NavigationDestination met Icons.settings van 'Settings' naar 'Instellingen'. Geen wijzigingen aan icoon, volgorde, providers of API's. Controleer of er bestaande widget-tests zijn die hardcoded op de Engelse tekst 'Settings' matchen en pas deze zo nodig aan als onderdeel van dit ontwikkelwerk.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[~]: run relevant tests (flutter niet beschikbaar in deze omgeving, zie hieronder)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `frontend/lib/screens/main_shell.dart`: label van de `NavigationDestination` met `Icons.settings` gewijzigd van `'Settings'` naar `'Instellingen'`. Icoon en volgorde ongewijzigd; geen andere schermen/providers/API's aangeraakt.
- `frontend/test/main_shell_test.dart`: bestaande widget-tests matchten hardcoded op de Engelse tekst `'Settings'`. Aangepast zodat ze het nieuwe label `'Instellingen'` verwachten en controleren dat `'Settings'` niet meer voorkomt (zowel de weergave-test als de tap-navigatie-test).
- Niet gedaan: tests lokaal draaien — de `flutter`/Dart SDK is niet geïnstalleerd in deze sandbox (`flutter: command not found`, geen flutter-binary op het systeem gevonden). De wijziging is beperkt en mechanisch (stringliteral in test en source), maar CI/reviewer moet `flutter test test/main_shell_test.dart` alsnog draaien ter bevestiging.
