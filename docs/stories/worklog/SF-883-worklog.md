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

## Review SF-884 (reviewer, 2026-07-09)

Geverifieerd: `git diff main...HEAD` bevat exact de geclaimde wijzigingen (main_shell.dart label, main_shell_test.dart, deze worklog). Geen scope-overschrijding, icoon/volgorde ongewijzigd, `settings_screen.dart` AppBar-titel was al "Instellingen".

[bug] `frontend/test/main_shell_test.dart:78-83` ("settings-tab blijft navigeerbaar via het settings-icoon"): na de labelwijziging faalt `expect(find.text('Instellingen'), findsOneWidget)` na de tap. `NavigationBar` in `main_shell.dart` gebruikt geen `labelBehavior`, dus de default (`alwaysShow`) toont het label "Instellingen" van de bottom-nav-destination permanent, terwijl `SettingsScreen`'s AppBar-titel ook "Instellingen" is (`settings_screen.dart:27`). Na de tap staan er dus 2 widgets met tekst "Instellingen" in de tree (nav-label + AppBar-titel), niet 1. `find.text('Instellingen')` zal `findsNWidgets(2)` opleveren i.p.v. `findsOneWidget`, waardoor deze test faalt zodra hij daadwerkelijk draait (kon niet lokaal geverifieerd worden, zie "Niet gedaan" hierboven). Vergelijk met de Events-tab (`events_screen.dart:20`), waar AppBar-titel "Events" toevallig ook het nav-label is — dat pad wordt echter niet op deze manier getest, dus dat conflict was tot nu toe onopgemerkt.
Suggestie voor fix: gebruik een specifiekere finder na de tap, bv. `find.descendant(of: find.byType(AppBar), matching: find.text('Instellingen'))` of `find.widgetWithText(NavigationDestination, 'Instellingen')` blijft aanwezig i.p.v. `find.text('Instellingen')` los te gebruiken.

[info] De kernwijziging (het label zelf) is correct en minimaal; alleen de nieuwe testassertie is stuk. Na fix van bovenstaande test-regel is deze story review-ready.

## Fix SF-884 (developer, 2026-07-09)

- `frontend/test/main_shell_test.dart`: reviewer-bug opgelost. De assertie na de tap gebruikte `find.text('Instellingen')`, wat na de labelwijziging 2 widgets matcht (bottom-nav-label + SettingsScreen AppBar-titel, beide "Instellingen"). Vervangen door `find.descendant(of: find.byType(AppBar), matching: find.text('Instellingen'))`, zodat alleen de AppBar-titel wordt gecontroleerd en de test ondubbelzinnig `findsOneWidget` oplevert.
- Geen overige wijzigingen; `main_shell.dart` (het label zelf) was al correct sinds de vorige commit en blijft ongewijzigd.
- Niet gedaan: tests lokaal draaien — `flutter`/Dart SDK ontbreekt nog steeds in deze sandbox. De fix is mechanisch (finder-specificiteit) en mechanisch geverifieerd door diff-review; CI/reviewer moet `flutter test test/main_shell_test.dart` bevestigen.

## Test SF-885 (tester, 2026-07-09)

- `git diff main...HEAD` bevat exact de geclaimde wijzigingen: `main_shell.dart` (label `'Settings'` → `'Instellingen'`), `main_shell_test.dart` (assertie-fix uit reviewronde), deze worklog. Geen scope-overschrijding.
- Flutter/Dart-SDK niet beschikbaar in deze testomgeving → widget-tests niet lokaal gedraaid; verificatie via browser op de preview (`https://pnf-pr-174.vdzonsoftware.nl`, PR 174).
- `oc get secret` faalde met "error loading config file .../.kube/config: is a directory" (bekend agent:local-mankement) → `TESTER_USERNAME`/`TESTER_PASSWORD` niet resolvebaar. Teruggevallen op wegwerpaccount `tester_sf-883`: geregistreerd via de UI, getest, en aan het eind opgeruimd met `DELETE /api/account/me` → HTTP 200.
- Browser-verificatie (Playwright, viewport 420x900, screenshots in `/work/screenshots`):
  - `06_main_shell_bottom_nav.png`: bottom-navigatiebalk toont "Instellingen" i.p.v. "Settings"; icoon (tandwiel) en volgorde (Feed, RSS, Podcast, Events, Instellingen) ongewijzigd.
  - `07_settings_tab_open.png`: na tap op de tab opent het instellingenscherm met AppBar-titel "Instellingen" (was al correct, ongewijzigd).
- Geen regressies waargenomen in de overige tabs (Feed/RSS/Podcast/Events) of in login/registratie-flow.
- Conclusie: acceptatiecriteria van SF-883/SF-884 zijn voldaan. Test-rapport: `tested`.
