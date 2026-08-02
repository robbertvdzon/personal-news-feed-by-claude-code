# SF-1768 - Worklog

Story-context bij eerste pickup:
Weergave-sectie bovenaan settings_screen plaatsen

Verplaats in frontend/lib/screens/settings_screen.dart het Weergave-blok (kop 'Weergave' + SwitchListTile 'Grote tekst' op appearance.largeFont/setLarge, nu r123-129 als laatste ListView-item ná de admin-only Beheer-sectie) naar de allereerste positie van de ListView, boven 'Over deze app'. De overige sectievolgorde (Over deze app -> Account -> Categorieen -> RSS feeds -> Achtergrond-taken -> Opruimen -> Debug -> [Beheer]) blijft ongewijzigd. Let op de divider-boekhouding: de Divider die nu vóór het Weergave-blok staat mag niet bovenaan het scherm terechtkomen - plaats de scheiding NA het Weergave-blok, vóór 'Over deze app'; zorg dat op de oude plek geen restant of dubbele Divider tussen Debug en Beheer achterblijft en dat de lijst netjes eindigt in zowel de admin- als niet-admin-variant. De switch blijft functioneel identiek (waarde uit appearanceProvider.largeFont, wijziging via setLarge(v), effect TextScaler.linear(1.38) in main.dart:139 ongemoeid). Werk als onderdeel van dit ontwikkelwerk de bestaande widgettests in frontend/test/settings_screen_test.dart bij: de twee positietests (r64-89 niet-admin, r91-106 admin) asserteren nu dat weergaveDy GROTER is dan debugDy/categoriesDy/beheerDy - draai die om naar 'Weergave staat vóór (kleinere dy dan) Over deze app, Account, Categorieen, Debug en, in de admin-variant, Beheer', inclusief passende testnamen/comments; laat de derde test (setLarge via _FakeAppearanceNotifier) ongewijzigd. Buiten scope: frontend-reader (geen settings-scherm), backend, appearanceProvider/AppearanceState-logica en SF-809-story-documentatie. Voer zelf een review-stap uit en verifieer lokaal: 'flutter test' en 'flutter analyze' in frontend/ groen zonder nieuwe warnings. Werk docs/stories/worklog/SF-1768-worklog.md bij met uitgevoerde stappen en rationale.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-1769 (development), 2026-08-02:
  - `frontend/lib/screens/settings_screen.dart`: Weergave-blok (kop + SwitchListTile
    "Grote tekst") verplaatst naar de eerste positie van de ListView. De Divider die eerst
    vóór het blok stond staat nu erná (vóór "Over deze app"), zodat er geen scheiding
    bovenaan het scherm komt. Op de oude plek is niets achtergebleven: de lijst eindigt op
    de admin-only Beheer-sectie, zonder dubbele of losse Divider in beide varianten.
    Switch functioneel identiek (appearanceProvider.largeFont / setLarge(v));
    `main.dart` (TextScaler.linear(1.38)) niet aangeraakt.
  - `frontend/test/settings_screen_test.dart`: beide positietests omgedraaid naar
    "Weergave staat vóór (kleinere dy dan) Over deze app / Account / Categorieën / Debug /
    Beheer", inclusief testnamen en comments; extra asserties dat de volgorde van de
    overige secties ongewijzigd is. Twee nieuwe tests borgen de divider-boekhouding
    (7 dividers niet-admin, 8 admin). De setLarge-test is ongewijzigd.
  - Verificatie: `flutter test` in frontend/ = 22 groen; `flutter analyze` = 7 pre-existing
    info-meldingen in andere bestanden, geen nieuwe warnings; vangnet
    `mvn -B --no-transfer-progress clean verify` in newsfeedbackend/newsfeedbackend =
    BUILD SUCCESS, exit 0, 0 failures/errors (61 e2e + unit, ~3:54 min).
  - `frontend/pubspec.lock` niet gewijzigd (geen lockfile-drift).
