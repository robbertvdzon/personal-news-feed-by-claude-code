# SF-1945 - Worklog

Story-context bij eerste pickup:
Rename asText() naar asString() in de 11 e2e-testbestanden

Meet eerst de baseline met `mvn -B --no-transfer-progress clean test` in newsfeedbackend/newsfeedbackend/ (verwacht: 92 [WARNING]-regels, Tests run: 102, Failures: 0, Errors: 0). Vervang daarna in src/test/kotlin/com/vdzon/newsfeedbackend/e2e/ alle 92 voorkomens van `.asText()` door `.asString()`, verdeeld over RequestsE2eTest.kt (21), PodcastGenerationE2eTest.kt (13), AdminE2eTest.kt (13), SettingsE2eTest.kt (12), RssItemsE2eTest.kt (12), RssRefreshE2eTest.kt (7), PodcastIngestE2eTest.kt (5), SharedFeedE2eTest.kt (4), FeedE2eTest.kt (3), E2eTestBase.kt (1), AuthE2eTest.kt (1). Alle voorkomens zijn de argumentloze vorm; identieke signatuur en semantiek, dus geen import- of typewijziging. Beperk de vervanging strikt tot src/test en match op `.asText()` (niet op de losse tekst `asText(`) om e2e/flutter-helpers.js (`hasText(`) en src/main niet te raken. Wijzig geen testlogica, asserties, testnamen of het aantal tests; voeg geen tests toe of verwijder er geen. Verifieer daarna: `grep -r 'asText(' src/test | wc -l` geeft 0, `mvn -B clean test` geeft nul [WARNING]-regels en nog steeds exact Tests run: 102, Failures: 0, Errors: 0, en `git diff --stat` toont uitsluitend de 11 genoemde bestanden. Draai `mvn verify` als Docker beschikbaar is; is dat niet zo, meld dat expliciet zodat CI het afdekt. Sluit af met een zelfreview op: diff bevat alleen de rename, src/main is ongewijzigd, en geen enkele nieuwe warning uit een andere bron blijft onbenoemd.

Stappenplan:
[x]: read issue and target docs
[x]: baseline meten (mvn clean test-compile: 92 [WARNING], allemaal asText)
[x]: implement requested changes (92x `.asText()` -> `.asString()` in 11 e2e-bestanden)
[x]: run relevant tests (mvn -B clean verify, incl. Testcontainers-e2e)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Baseline gemeten met `mvn -B --no-transfer-progress clean test-compile`: exact 92
  `[WARNING]`-regels, allemaal de deprecation van `JsonNode.asText()` (Jackson 3).
  Geen enkele warning uit een andere bron.
- Rename uitgevoerd met `grep -rl '\.asText()' src/test --include=*.kt | xargs sed -i
  's/\.asText()/.asString()/g'`. Bewust gematcht op `.asText()` (met punt en lege
  argumentenlijst) en strikt binnen `src/test`, zodat `src/main` en `e2e/flutter-helpers.js`
  (`hasText(`) niet geraakt worden. Pure naamswijziging: identieke signatuur en semantiek in
  Jackson 3, dus geen import- of typewijziging.
- Geen tests toegevoegd, verwijderd, hernoemd of van assertie veranderd: dit is een
  gedragsneutrale rename en de bestaande 102 unit- + 61 e2e-tests zijn zelf het vangnet.
  Nieuwe tests zouden hier niets extra's bewijzen en zijn expliciet buiten scope.

Resultaat / verificatie (branch ai/SF-1945, 2026-08-05):
- `grep -r 'asText(' src/test | wc -l` -> `0`; `grep -ro '\.asString()' src/test | wc -l` -> `92`.
- `mvn -B --no-transfer-progress clean verify` -> **BUILD SUCCESS**, exitcode 0.
  - Unit (surefire): `Tests run: 102, Failures: 0, Errors: 0, Skipped: 0`.
  - E2e (failsafe, Testcontainers/Docker beschikbaar): `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0`.
  - `[WARNING]`-regels in de Maven-output: **0** (was 92).
  - Totale looptijd ~4:14 min.
- `git diff --stat` raakt uitsluitend de 11 e2e-testbestanden (86 regels +/-, 92 voorkomens);
  `src/main/` is ongewijzigd.
- Zelfreview: elke gewijzigde regel bevat alleen de rename — een diff-filter op regels
  zonder `asText()`/`asString()` levert nul treffers op.

## Testronde SF-1947 (tester, 2026-08-05)

Onafhankelijk nagemeten op branch `ai/SF-1945` (HEAD `cb9a60d`), niets gewijzigd behalve dit worklog.

- **AC1 — nul warnings**: `mvn -B --no-transfer-progress clean verify` (exact het commando uit
  `.factory/verification.yaml`, id `backend-maven-verify`) → **0** `[WARNING]`-regels en 0 `[ERROR]`-regels
  in de volledige output. Was 92.
- **AC2 — geen `asText(` meer**: `grep -r 'asText(' newsfeedbackend/newsfeedbackend/src/test | wc -l` → `0`;
  `grep -ro 'asString(' .../src/test | wc -l` → `92`. Buiten de backend is er geen `asText`-gebruik;
  `e2e/flutter-helpers.js:228` (`hasText(`) is terecht ongemoeid gelaten.
- **AC3 — unit groen**: surefire `Tests run: 102, Failures: 0, Errors: 0, Skipped: 0`. Exact het
  baseline-aantal; geen test toegevoegd, verwijderd of hernoemd.
- **AC4 — verify groen**: failsafe/e2e over Testcontainers `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0`
  (10 e2e-klassen). `BUILD SUCCESS`, exitcode 0, looptijd 03:45. Geen flakes waargenomen — met name
  `AdminE2eTest` (bekende socket-timeout-flake) was in één run groen (10/10).
- **AC5 — diff-scope**: `git diff --stat main...HEAD` raakt uitsluitend de 11 e2e-testbestanden + dit worklog;
  `src/main/` heeft 0 diff. Gedragsneutraliteit hard bewezen door de 86 `-`-regels en 86 `+`-regels
  paarsgewijs te normaliseren (`asText()`/`asString()` → `X()`): het verschil is leeg, dus élke gewijzigde
  regel is puur de methodenaam.
- **Live sanity op preview** `https://pnf-pr-208.vdzonsoftware.nl`: `/` → 200, `/actuator/health` → 200
  (status UP, db UP), `/api/feed` zonder token → 403. Geen browser/screenshot-bewijs: de story raakt geen
  Dart-code en geen `src/main`, dus er is geen UI-gedrag om te observeren.

Oordeel: **tested** — alle 5 acceptatiecriteria voldaan, vangnet exitcode 0 met 0 failures en 0 errors.
