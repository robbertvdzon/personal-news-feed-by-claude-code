# SF-1945 - [Audit] Testbuild schoon maken: Jackson 3 asText() → asString() in de e2e-tests

## Story

[Audit] Testbuild schoon maken: Jackson 3 asText() → asString() in de e2e-tests

<!-- refined-by-factory -->

## Samenvatting

Bij het bouwen van de backend-tests verschijnen 92 waarschuwingen. Ze komen allemaal uit dezelfde verouderde functienaam die in de nieuwe versie van een gebruikte bibliotheek anders heet.

Die stapel waarschuwingen is vooral vervelend omdat er echte, nieuwe waarschuwingen tussen kunnen wegvallen zonder dat iemand het merkt. Dat is recent ook gebeurd.

Deze story ruimt de stapel op met een pure naamswijziging: dezelfde functie, dezelfde werking, alleen de nieuwe naam. Er verandert niets aan wat de tests controleren en niets aan de applicatie zelf.

Resultaat: de testbuild is weer schoon, zodat een nieuwe waarschuwing meteen opvalt.

## Scope

- Vervang in `newsfeedbackend/newsfeedbackend/src/test/` alle 92 aanroepen van `JsonNode.asText()` door `JsonNode.asString()`.
- Betrokken bestanden (allemaal in `src/test/kotlin/com/vdzon/newsfeedbackend/e2e/`), met geverifieerd aantal voorkomens:
  - `RequestsE2eTest.kt` (21)
  - `PodcastGenerationE2eTest.kt` (13)
  - `AdminE2eTest.kt` (13)
  - `SettingsE2eTest.kt` (12)
  - `RssItemsE2eTest.kt` (12)
  - `RssRefreshE2eTest.kt` (7)
  - `PodcastIngestE2eTest.kt` (5)
  - `SharedFeedE2eTest.kt` (4)
  - `FeedE2eTest.kt` (3)
  - `E2eTestBase.kt` (1)
  - `AuthE2eTest.kt` (1)
- Alle 92 voorkomens zijn de argumentloze vorm `asText()`; er is geen `asText(default)` in `src/test`. De vervanging is dus één-op-één `asText()` → `asString()`.
- Buiten scope: testlogica, asserties, testnamen, toevoegen/verwijderen van tests, `src/main` (bevat al 0 `asText(`-treffers sinds SF-1338), en het toevoegen van een build-gate die op warnings faalt (aparte story waard).

Precedent: SF-1338 voerde exact deze rename door in `src/main`; volg dezelfde aanpak.

## Acceptance criteria

1. `mvn -B clean test` (in `newsfeedbackend/newsfeedbackend/`) produceert **nul** `[WARNING]`-regels in de output (nu: 92, allemaal `asText`).
2. `grep -r 'asText(' newsfeedbackend/newsfeedbackend/src/test | wc -l` geeft `0`.
3. `mvn test` blijft groen met exact `Tests run: 102, Failures: 0, Errors: 0` — er wordt geen test toegevoegd, verwijderd, hernoemd of van assertie veranderd.
4. `mvn verify` (inclusief de failsafe/e2e-suite, Testcontainers/Docker) blijft groen in CI.
5. Geen enkele wijziging onder `newsfeedbackend/newsfeedbackend/src/main/`; de diff raakt uitsluitend de 11 bestanden uit Scope.

## Aannames

- `JsonNode.asString()` is in Jackson 3 (`tools.jackson`) de directe vervanger van `asText()` met identieke signatuur en semantiek; de rename is gedragsneutraal en vereist geen import- of typewijzigingen.
- De huidige baseline is gemeten op branch `ai/SF-1945` op 2026-08-05: 92 warnings, 102 tests groen, 0 warnings buiten `asText`. AC1 en AC3 zijn daarmee reële, haalbare doelen en geen inschatting.
- Alleen `.asText()`-aanroepen op `JsonNode` worden geraakt; er zijn in `src/test` geen gelijknamige aanroepen op andere types (warning-aantal en voorkomen-aantal zijn beide exact 92).
- AC4 wordt in CI geverifieerd via `.factory/verification.yaml` (`backend-maven-verify`); lokaal draaien vereist Docker.

## Eindsamenvatting

Diff-scope en AC2 zelf geverifieerd: 11 testbestanden + worklog, `src/main` ongewijzigd, 0 `asText(`-treffers.

# SF-1945 — Eindsamenvatting

## Wat is gebouwd
De backend-testbuild produceerde 92 `[WARNING]`-regels, allemaal afkomstig van de in Jackson 3 verouderde `JsonNode.asText()`. Alle 92 aanroepen in `newsfeedbackend/newsfeedbackend/src/test/kotlin/.../e2e/` zijn vervangen door `asString()`, verdeeld over 11 e2e-testbestanden (RequestsE2eTest 21, PodcastGenerationE2eTest 13, AdminE2eTest 13, SettingsE2eTest 12, RssItemsE2eTest 12, RssRefreshE2eTest 7, PodcastIngestE2eTest 5, SharedFeedE2eTest 4, FeedE2eTest 3, E2eTestBase 1, AuthE2eTest 1). De testbuild is daarmee weer volledig warning-vrij.

## Gemaakte keuzes
- **Pure rename, geen gedragswijziging**: `asString()` heeft in Jackson 3 identieke signatuur en semantiek; geen import- of typewijzigingen nodig. Volgt het precedent van SF-1338, dat dezelfde rename in `src/main` deed.
- **Strikte match op `.asText()`** (met punt en lege argumentenlijst) en strikt binnen `src/test`, zodat `src/main` en `e2e/flutter-helpers.js` (`hasText(`) gegarandeerd ongemoeid bleven.
- **Geen nieuwe tests toegevoegd**: bij een gedragsneutrale rename vormen de bestaande 102 unit- en 61 e2e-tests zelf het vangnet.

## Wat is getest
Onafhankelijk nagemeten door de tester op branch `ai/SF-1945`; alle 5 acceptatiecriteria voldaan:
- `mvn -B --no-transfer-progress clean verify` → **BUILD SUCCESS**, exitcode 0, **0 `[WARNING]`-regels** (was 92) en 0 `[ERROR]`-regels.
- Unit (surefire): `Tests run: 102, Failures: 0, Errors: 0, Skipped: 0` — exact het baseline-aantal.
- E2e (failsafe, Testcontainers/Docker): `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0` over 10 e2e-klassen; geen flakes, ook de bekend-flaky `AdminE2eTest` was groen.
- `grep -r 'asText(' src/test` → `0`; 92 `asString()`-treffers ervoor in de plaats.
- Gedragsneutraliteit hard bewezen: de 86 verwijderde en 86 toegevoegde regels zijn paarsgewijs genormaliseerd op de methodenaam; het verschil is leeg, dus élke gewijzigde regel bevat uitsluitend de rename. `src/main/` heeft 0 diff.
- Live sanity op preview `pnf-pr-208`: `/` → 200, `/actuator/health` → 200 (UP, db UP), `/api/feed` zonder token → 403. Geen screenshot-bewijs, terecht: de story raakt geen UI-code.

## Bewust niet gedaan
- Geen wijziging aan testlogica, asserties, testnamen of het aantal tests.
- Geen wijziging onder `src/main/` (bevatte al 0 treffers sinds SF-1338).
- **Geen build-gate toegevoegd die op warnings faalt** — expliciet buiten scope gehouden en een aparte story waard. Zonder zo'n gate kan een nieuwe warning op termijn opnieuw ongemerkt binnensluipen; dit is de enige openstaande restrisico-post.

<!-- deploy-summary:start -->
Er is opgeruimd in de automatische controles van de app. Voor jou als gebruiker verandert er niets zichtbaars: de app werkt precies hetzelfde als voorheen. Wel merken we voortaan sneller op wanneer er iets misgaat, omdat er geen ruis meer tussen de meldingen zit.
<!-- deploy-summary:end -->
