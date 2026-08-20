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

Review (SF-2222, reviewer, 2026-08-20) — akkoord, geen blockers:
- Revisie-anker: `git rev-parse HEAD^{tree}` = `e4f9e8e3…` == `testedTreeSha` uit
  [FACTORY VERIFICATION EVIDENCE] (`backend-maven-verify` passed, exit 0). Het
  harnessbewijs hoort dus bij precies deze diff.
- Zelf gedraaid (frontend valt buiten `.factory/verification.yaml`):
  `flutter test` in `frontend-reader/` = **18 groen** (de acht `time_format_test.dart`-
  tests staan er per naam bij), `flutter test` in `frontend/` = **37 groen**,
  `flutter analyze` in `frontend-reader/` = "No issues found". `git status` na die
  runs schoon: geen `pubspec.lock`-drift, geen `coverage/`-map (AC 5, 6, 11).
- AC 4 hard gemeten: `git diff main...HEAD -- frontend-reader/` toont 26 toegevoegde
  en 0 verwijderde regels — geen enkele bestaande assertie geraakt.
- Alle regelverwijzingen in de nieuwe comments nagelopen tegen de bron en ze kloppen
  exact: reader `lib/time_format.dart:11` (`'$dagen dagen geleden'`, geen enkelvoud),
  hoofd-app `:17` (`diff.isNegative` → `'zojuist'`), `:18` (`'net binnen'`),
  `:21` (`'5 minuten geleden'`), `:27` (`inDays < 3`), `:29` (`'1 dag geleden'`).
- Deel 3 gecontroleerd met een genormaliseerde diff (SF-2208/2207 → `@@A`,
  SF-2187/2186 → `@@B`): elke `-`-regel heeft een exact gepaarde `+`-regel, dus
  uitsluitend het storynummer wijzigt (AC 9). 12 nieuwe SF-2207- en 7 nieuwe
  SF-2186-treffers; beide AC-greps geven 0 treffers (AC 7, 8) en
  `docs/stories/SF-2186-*` houdt zijn 4 correcte SF-2187-verwijzingen.
- [info] Het comment schrijft "Karakteriseringstest" met hoofdletter aan het begin
  van de zin; een hoofdlettergevoelige grep op het exacte woord vindt hem daardoor
  niet. Inhoudelijk voldoet AC 2 (woord, `'1 dag geleden'` en het correctieverbod
  staan er alle drie).
- [info] Story-log en worklog noemen `mvn … clean test` (141), terwijl de
  developer-handover `clean verify` meldt (141 unit + 77 e2e). Puur prozaverschil;
  het harnessbewijs is `backend-maven-verify` en dat is groen.

## SF-2223 — Story-brede test (tester, 2026-08-20)

Akkoord. Alle 11 AC's geverifieerd op branch `ai/SF-2221` (HEAD `29f261b`).

**Testruns (alle exitcode 0, 0 failures, 0 errors):**
- `flutter test` in `frontend-reader/`: **18 tests groen** (baseline 17), waaronder de
  nieuwe `1 dag geleden geeft "1 dagen geleden"` — AC 1, 5.
- `flutter test` in `frontend/`: 37 tests groen — AC 6.
- `mvn -B --no-transfer-progress clean test` in `newsfeedbackend/newsfeedbackend/`:
  `Tests run: 141, Failures: 0, Errors: 0`, BUILD SUCCESS (26,9 s) — AC 6.

**Statische verificatie:**
- AC 2/3: comments nagelopen tegen de bron; alle regelverwijzingen kloppen —
  reader `lib/time_format.dart:8` (`'zojuist'`), `:9` (`'min geleden'`),
  `:11` (`inDays <= 3`, geen enkelvoud); hoofd-app `frontend/lib/util/time_format.dart:17`
  (`diff.isNegative` → `'zojuist'`), `:18` (`'net binnen'`), `:21` (`'5 minuten geleden'`),
  `:27` (`inDays < 3` → absolute datum), `:29` (`'1 dag geleden'`).
- AC 4: `git diff main...HEAD -- frontend-reader/test/time_format_test.dart` bevat
  uitsluitend toegevoegde regels; geen bestaande assertie geraakt.
- AC 7/8: beide AC-greps geven 0 treffers; `grep -rn "SF-2187" docs/stories/` houdt de
  bestaande treffers in `SF-2186-Audit-…md` en `SF-2186-worklog.md`.
- AC 9: `--word-diff` toont uitsluitend `SF-2208→SF-2207` en `SF-2187→SF-2186` als
  gewijzigde tokens; 12 resp. 7 treffers op exact de in de scope genoemde regels
  (incl. de dubbele vervanging op `specs/backend-technical-spec.md:588`).
- AC 10: `docs/factory/development.md:185` = 18 tests, tabelregel `time_format_test.dart` = 8;
  de regels 2 (`widget_test.dart`) en 8 (`read_store_test.dart`) staan ongewijzigd.
- AC 11: `git status --porcelain` is leeg na alle testruns — geen `pubspec.lock`-drift,
  geen `coverage/`-map.

**Omgeving:** preview `https://pnf-pr-236.vdzonsoftware.nl` is live (HTTP 200,
`/api/version` sha `7c6eb36`, springVersion 4.0.7); screenshot van het loginscherm in
`/work/screenshots/SF-2223-preview-live.png`. `git diff 7c6eb36..HEAD` raakt alleen dit
worklog, dus het preview-bewijs geldt onverkort voor HEAD. Een gedragstest in de browser
is hier niet van toepassing: de story wijzigt nul regels productiecode (alleen
`frontend-reader/test/` en documentatie), en de reader-app heeft sowieso geen per-PR
preview (`deploy/base/reader-route.yaml` pint de vaste host `reader.vdzonsoftware.nl`).
Geen flakes waargenomen; geen DB-mutaties, geen login nodig.
