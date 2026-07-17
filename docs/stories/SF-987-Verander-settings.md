# SF-987 - Verander settings

## Story

Verander settings

<!-- refined-by-factory -->

## Scope
Wijzig in de settingspagina (`frontend/lib/screens/settings_screen.dart`) de sectiekop 'Account' naar 'Account Settings'. Dit betreft uitsluitend de tekst van de `Text`-widget op regel 35; geen andere wijzigingen aan layout, logica of backend.

## Acceptance criteria
- In `frontend/lib/screens/settings_screen.dart` toont de sectiekop boven het account-blok (username + uitlog-knop) de tekst 'Account Settings' in plaats van 'Account'.
- Geen andere teksten, providers, backend-endpoints of overige schermen zijn gewijzigd.
- De rest van de settingspagina (Over deze app, Categorieën, RSS-feeds, Podcast-bronnen, Weergave, etc.) blijft functioneel ongewijzigd.

## Aannames
- 'Account Settings' wordt letterlijk in het Engels overgenomen zoals in de issue-omschrijving vermeld, ook al is de rest van de pagina Nederlandstalig (bv. 'Instellingen', 'Over deze app').
- Er is geen i18n/vertaalbestand in gebruik voor deze tekst; de string staat inline in de widget-code.

<!-- test-feedback:start -->
## Test-feedback
De story-brede test toont: de functionele wijziging (`Account` → `Account Settings`) is correct en scope-conform, en `flutter test` (16/16) en de 61 backend-tests slagen allemaal (0 failures, 0 errors). Echter, het verplichte backend-vangnet `mvn verify` (uit `.factory/verification.yaml`) eindigt met **exitcode 1 / BUILD FAILURE** door een JaCoCo IT-coverage-rapportagefout (`Unknown block type 64`) ná de teststap — reproduceerbaar over 2 volledige runs. Volgens de absolute gate ("elke rode run = test-rejected, ook als pre-existing/ongerelateerd") moet dit worden afgekeurd; het is een build/tooling-probleem, geen testfout, maar valt buiten de tester-scope om te fixen.

{"agent_tips_update":[{"category":"testing","key":"pnf-backend-verify-jacoco-it-report-block-type-64","content":"Op deze runner is Docker/Testcontainers géén blocker meer voor `mvn verify` in newsfeedbackend/newsfeedbackend (61/61 tests slagen, 0 failures/errors) — de eerdere agent-tip 'mvn verify vereist Docker' is achterhaald. Wel faalt de build daarna alsnog (exitcode 1, BUILD FAILURE) op de jacoco-maven-plugin:0.8.13:report-integration-stap met 'Error while creating report: Unknown block type 64' (corrupt/onleesbaar target/jacoco-it.exec). Reproduceerbaar over meerdere runs, onafhankelijk van de code-diff (ook bij pure frontend-only stories zoals SF-987/SF-1031). Volgens de absolute test-gate leidt dit tot test-rejected totdat de jacoco-configuratie of .factory/verification.yaml is gefixt door developer/infra."}]}
{"phase":"test-rejected"}
<!-- test-feedback:end -->

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summarized"}
