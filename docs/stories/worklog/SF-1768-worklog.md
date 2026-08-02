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
- SF-1769 review (reviewer, 2026-08-02): akkoord, geen blockers.
  - Volledige story-diff `git diff main...HEAD` beoordeeld (4 bestanden, alleen
    `settings_screen.dart` + `settings_screen_test.dart` als code).
  - Sectievolgorde geverifieerd in de code: Weergave → Over deze app → Account →
    Categorieën → RSS feeds → Achtergrond-taken → Opruimen → Debug → [admin] Beheer.
    Dividers: 8 in het bestand, geen bovenaan, geen restant/dubbele tussen Debug en
    Beheer, lijst eindigt in beide varianten netjes op de laatste sectie.
  - Switch functioneel identiek (`appearance.largeFont` / `setLarge(v)`), `main.dart`
    niet aangeraakt; scope niet overschreden (geen backend/frontend-reader/provider-
    wijzigingen, SF-809-story-doc ongemoeid).
  - Gerichte hercontrole: `flutter test` in frontend/ = 22 tests groen (exit 0);
    `flutter analyze` = 7 issues, allemaal pre-existing `info` in ws_client.dart,
    feed_screen.dart, podcast_detail_screen.dart, rss_detail_screen.dart en
    rss_screen.dart — niets in `settings_screen.dart`. Geen pubspec.lock-drift.
- SF-1770 (story-brede test, tester, 2026-08-02): akkoord, geen bugs gevonden.
  - Frontend-checks lokaal: `flutter test` in frontend/ = 22 tests groen (exit 0),
    incl. de 4 settings_screen-positie/divider-tests; `flutter analyze` = dezelfde
    7 pre-existing infos in andere bestanden, geen nieuwe warnings.
  - Live geverifieerd op preview `https://pnf-pr-203.vdzonsoftware.nl` met Playwright
    (420x900). Login-modus: **wegwerp-account** (`tester_sf-1768b`), omdat
    `oc get secret newsfeed-api-keys -n pnf-pr-203` Forbidden is voor de
    claude-agent-SA en TESTER_USERNAME/TESTER_PASSWORD niet gezet zijn. Account na
    afloop verwijderd via `DELETE /api/account/me` (200).
  - "Over deze app" toonde buildhash `0ef1a8d` voor frontend én backend = branch-HEAD,
    dus de preview draaide de geteste revisie.
  - Gemeten sectieposities in de live UI (top van scherm): Weergave y=72 →
    Over deze app y=164 → Account y=314 → Categorieën y=454 → RSS feeds y=562 →
    Achtergrond-taken y=670 → Opruimen y=826 → Debug y=918. Weergave is dus de
    eerste sectie; onderlinge volgorde van de rest ongewijzigd. Na doorscrollen
    eindigt de lijst op Debug (niet-admin), geen restant van de Weergave-sectie
    onderaan en geen losse/dubbele divider.
  - "Grote tekst"-schakelaar functioneel: aanzetten vergroot zichtbaar alle teksten
    (sectie-afstanden lopen van y=164/314/454 naar y=176/365/518), uitzetten herstelt
    de oorspronkelijke weergave. Admin-only Beheer-variant is niet live te testen met
    een wegwerp-account en is gedekt door de widgettests.
  - Screenshots in `/work/screenshots`: 004-settings-top, 005-grote-tekst-aan,
    006-settings-bottom, 007-settings-top-scrolled-back, 008-grote-tekst-uit.
  - Geen code/tests/infra gewijzigd door de tester; alleen dit worklog.
