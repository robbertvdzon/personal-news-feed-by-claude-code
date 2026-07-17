# SF-1031 - Sectiekop 'Account' → 'Account Settings' in settingspagina

Stappenplan:
[x]: lees .task.md, docs/factory en agent-tips
[x]: verifieer bestaande implementatie tegen scope
[x]: analyseer test-rejected feedback (mvn verify / JaCoCo `Unknown block type 64`)
[x]: draai vangnet (backend mvn verify + frontend flutter test)
[x]: fix vangnet-blocker (`.factory/verification.yaml`)
[x]: story-log bijgewerkt

Done / rationale:
- De kernwijziging was al aanwezig op de branch: `frontend/lib/screens/settings_screen.dart:35` toont `'Account Settings'` i.p.v. `'Account'`, en `frontend/test/settings_screen_test.dart:85` gebruikt `find.text('Account Settings')` in de positie-assertie t.o.v. Categorieën/Weergave. Geen andere teksten/layout/logica/backend gewijzigd (bevestigd via grep op `'Account'` in `frontend/`, `e2e/`, `specs/`, `docs/`).
- De laatste tester-run (SF-1032) gaf `test-rejected`: de functionele wijziging en alle 61 backend-tests + 16 frontend-tests waren groen, maar `mvn -B --no-transfer-progress verify` (het vangnet uit `.factory/verification.yaml`) eindigde met exitcode 1 door een JaCoCo `report-integration`-fout ("Error while creating report: Unknown block type 64"), ná de teststap.
- Root cause gevonden: `.factory/verification.yaml` draaide `verify` zonder voorafgaande `clean`. Een leftover/corrupt `target/jacoco-it.exec` van een eerdere (deels afgebroken) run in dezelfde werkdirectory laat JaCoCo's `report-integration`-mojo falen bij het inlezen van dat exec-bestand, onafhankelijk van de code-diff — reproduceerbaar bevestigd door zelf een corrupte `target/` te herstellen: met een vervuilde `target/` faalde `mvn verify`, na `rm -rf target` (of `mvn clean verify`) slaagde de exact dezelfde build twee keer op rij (`BUILD SUCCESS`, 61/61 tests, 0 failures/errors).
- Fix: `.factory/verification.yaml` — `clean` toegevoegd vóór `verify` in de `backend-maven-verify`-argv, zodat elke vangnet-run start vanaf een schone `target/` en niet meer kan struikelen over stale/corrupte JaCoCo-exec-bestanden van een vorige run. Geen wijziging aan applicatiecode, testcode of het aantal/soort tests.
- Herverificatie na de fix: `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend/` — **BUILD SUCCESS**, `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0`, JaCoCo `report` + `report-integration` beide succesvol.
- Frontend-vangnet herbevestigd: `flutter clean && flutter pub get && flutter test` in `frontend/` — **16/16 groen** (de eerdere `Matrix4`-laadfout bleek eveneens stale build-cache in `.dart_tool`, opgelost door `flutter clean`; geen codewijziging nodig). `flutter pub get` liet `pubspec.lock` ongewijzigd — lockfile blijft reproduceerbaar stabiel, conform de eerder gedocumenteerde product owner-beslissing (issue comment 1274) om de 11 transitieve dependency-bumps te behouden.
- Geen verdere codewijzigingen nodig voor de kernscope van SF-1031; de sectiekop-implementatie voldeed al aan alle acceptatiecriteria. De enige wijziging in deze run is de vangnet-configuratiefix die de eerdere `test-rejected`-blocker (buiten scope van de tekstwijziging zelf) wegneemt.
