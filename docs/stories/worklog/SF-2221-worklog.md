# SF-2221 - Worklog

Story-context bij eerste pickup:
Karakteriseringstest, twin-drift-comments en storynummercorrecties

Zuiver test- en documentatiewerk; geen wijziging in frontend-reader/lib/, frontend/lib/, newsfeedbackend/ of deploy-manifesten, geen gedragswijziging.

1) frontend-reader/test/time_format_test.dart: voeg EEN test toe in de stijl van de bestaande zeven, met de bestaande isoAgo(Duration)-helper, die asserteert dat formatRelativeTime(isoAgo(const Duration(days: 1))) gelijk is aan '1 dagen geleden'. Zet er een comment bij dat letterlijk het woord 'karakteriseringstest' bevat, het hoofd-app-antwoord '1 dag geleden' (frontend/lib/util/time_format.dart:29) noemt, en expliciet zegt dat de assertie niet gecorrigeerd mag worden zonder dat de implementatie meeverandert. Volg toon en vorm van het precedent in frontend-reader/test/read_store_test.dart:150-155; de norm staat op docs/factory/technical-spec.md:96.

2) Zet in hetzelfde bestand bij de drie bestaande tests een comment met het afwijkende hoofd-app-antwoord: Duration.zero (:22-24) -> 'net binnen' (frontend/lib/util/time_format.dart:18) en vermeld daarbij dat 'zojuist' in de hoofd-app juist het negatief-tijdsverschil-geval is (:17), een guard die de reader mist; 5 minuten (:26-29) -> '5 minuten geleden' (:21); precies 3 dagen (:36-41) -> de absolute datum met grens < 3 (:27), waarbij de bestaande toelichting over de <= 3-grens (:37-38) blijft staan en wordt aangevuld. Wijzig GEEN enkele bestaande assertie: git diff op dit bestand mag alleen toegevoegde regels tonen.

3) Vervang SF-2208 -> SF-2207 op precies deze 12 plekken: specs/backend-technical-spec.md:320, :321, :572, :582, :584, :588; specs/backend-functional-spec.md:65; specs/frontend-spec.md:81; docs/factory/technical-spec.md:100; docs/onboarding-senior-developer.md:113; docs/factory/agents/tester.md:58; e2e/scenarios/start-scenario.md:21. Vervang SF-2187 -> SF-2186 op precies deze 7 plekken: specs/backend-technical-spec.md:567, :574, :588; specs/backend-functional-spec.md:82, :86; specs/frontend-spec.md:312; docs/onboarding-senior-developer.md:112. LET OP: specs/backend-technical-spec.md:588 bevat BEIDE nummers in een regel en heeft dus twee vervangingen. Puur tekstueel: alleen het nummer verandert, de omringende zin blijft woordelijk gelijk. Laat docs/stories/SF-2186-*.md en docs/stories/worklog/SF-2186-worklog.md ONGEMOEID (daar is SF-2187 een correcte verwijzing naar de eigen subtaak); gebruik dus geen repo-brede sed.

4) docs/factory/development.md: regel 185 'samen **17 tests**' wordt 18 en de tabelregel voor time_format_test.dart wordt 7 -> 8; de andere twee tabelregels (2 en 8) blijven staan.

Repareer niets: de meervoudsfout '1 dagen geleden' en de <= 3 / < 3-divergentie worden vastgelegd, niet gefixt; het samenvoegen van de twee time_format.dart-varianten valt buiten scope.

Verifieer voor het afronden zelf: flutter test in frontend-reader/ groen met 18 tests (baseline 17); grep -rn "SF-2208" . --exclude-dir=.git --exclude-dir=stories --exclude=.task.md geeft nul treffers; idem voor SF-2187, terwijl grep -rn "SF-2187" docs/stories/ zijn bestaande treffers ongewijzigd houdt. Zet de lockfile-drift terug (git checkout -- frontend-reader/pubspec.lock, container draait Flutter 3.44.7 vs CI 3.35.0) en laat geen coverage/-map achter. Sluit af met een eigen reviewslag over de volledige diff.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-2222 (developer, 2026-08-20) — vier delen, allemaal test-/documentatiewerk:
  1. `frontend-reader/test/time_format_test.dart`: karakteriseringstest voor precies
     één dag toegevoegd (`'1 dagen geleden'`), met comment dat het woord
     "karakteriseringstest" letterlijk noemt, verwijst naar het hoofd-app-antwoord
     `'1 dag geleden'` (`frontend/lib/util/time_format.dart:29`) en verbiedt de
     assertie los te "corrigeren".
  2. Twin-drift-comments bij de drie bestaande tests (`Duration.zero`, 5 minuten,
     precies 3 dagen) met het afwijkende hoofd-app-antwoord; bij de eerste ook dat
     `'zojuist'` daar het negatief-tijdsverschil-geval is (`:17`). Geen assertie
     gewijzigd — 26 toegevoegde, 0 verwijderde regels.
  3. SF-2208 → SF-2207 (12 regels) en SF-2187 → SF-2186 (7 regels) in de levende
     documentatie; per bestand vervangen zodat `docs/stories/SF-2186-*` en
     `docs/stories/worklog/SF-2186-worklog.md` ongemoeid blijven.
  4. `docs/factory/development.md`: 17 → 18 tests en tabelregel 7 → 8.
- Bewust NIET gefixt: de meervoudsfout `'1 dagen geleden'` en de `<= 3`/`< 3`-divergentie
  worden vastgelegd, niet gerepareerd (huisregel `docs/factory/technical-spec.md:96`).
- Vangnet: reader 18 groen, frontend 37 groen, `mvn -B --no-transfer-progress clean test`
  BUILD SUCCESS met 141 tests / 0 failures / 0 errors. `frontend-reader/pubspec.lock`
  teruggezet; geen `coverage/`-map achtergebleven.
