# SF-282 - Worklog

Story-context bij eerste pickup:
Herschrijf tester-loginflow naar vaste test-user uit secret

Wijzig docs/factory/agents/tester.md en de tester-system-prompt in deploy/claude-runner/runner.sh (bash-tak ~163-226 en prompt-tekst ~460-760). Maak als DEFAULT: tester leest TESTER_USERNAME/TESTER_PASSWORD (letterlijke namen, geen PNF_-prefix) read-only uit secret newsfeed-api-keys van de preview-namespace (oc get secret ... -o jsonpath base64 -d) en logt via de Flutter-UI in op PREVIEW_URL - geen DB-mutatie, geen wachtwoord-reset, geen guard-check, geen PREVIEW_DB_BRANCH-marker-afhankelijkheid. Namespace/PR runner-agnostisch resolven (${SF_PREVIEW_NAMESPACE:-pnf-pr-<PR>} / ${SF_PR_NUMBER:-PR_NUMBER}). Behoud de wegwerp-account-flow (tester_<lowercase-STORY_ID> + DELETE /api/account/me) uitsluitend als laatste fallback bij ontbrekende/onleesbare creds, met expliciete melding. Verwijder of markeer als deprecated: robbert-wachtwoord-reset, VEILIGHEIDSGUARD-sectie en bijbehorende bash-resolutie (PREVIEW_DB_URL/PREVIEW_DB_BRANCH/PREVIEW_DB_GUARD + preview-db-guard.py-aanroep). Raak preview-ns-labeller-branch-creatie en preview-db-guard.py zelf NIET aan. Borg dat tester.md één-op-één consistent is met de gezaghebbende runner.sh-prompt. Voer de interne review-stap binnen deze subtaak uit. Dekt AC1-AC5.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **runner.sh bash-tak (~160-226):** de SF-229 preview-branch-DB-resolutie +
  preview-db-guard.py-aanroep + PREVIEW_DB_URL/BRANCH/GUARD-exports verwijderd.
  Vervangen door read-only inlezen van TESTER_USERNAME/TESTER_PASSWORD uit het
  `newsfeed-api-keys`-secret van `${SF_PREVIEW_NAMESPACE:-pnf-pr-<PR>}`,
  geëxporteerd voor de tester. Wachtwoordwaarde wordt nooit gelogd, alleen
  aan/afwezigheid. Bij ontbrekende creds: logregel die de wegwerp-fallback
  aankondigt.
- **runner.sh tester-system-prompt (~456-760):** VEILIGHEIDSGUARD-sectie en
  robbert-reset-INLOG-MODI vervangen door (1) een "PREVIEW vs PRODUCTIE"-sectie
  (alleen preview, geen DB-mutaties) en (2) een INLOG-MODI-sectie met de
  test-user-uit-secret als DEFAULT en de wegwerp-user als laatste fallback met
  expliciete melding. Env-var-docs van PREVIEW_DB_* vervangen door
  TESTER_USERNAME/TESTER_PASSWORD. Login-subsectie en het Playwright-
  voorbeeldscript bijgewerkt: default test-user (niet registreren, niet
  verwijderen), wegwerp-fallback registreert + DELETE /api/account/me.
- **tester.md:** volledig herschreven en consistent gemaakt met de
  runner.sh-prompt (zelfde default/fallback, zelfde secret-keys, zelfde
  preview-only-regel).
- **Niet aangeraakt:** `deploy/claude-tester/preview-db-guard.py` en de
  preview-ns-labeller-branch-creatie blijven ongewijzigd.

Validatie:
- `bash -n deploy/claude-runner/runner.sh` → OK (prompt-string met veel
  `$`/`\"`-escapes parseert correct).
- `python3 -m unittest test_preview_db_guard` (deploy/claude-tester) → 18 tests OK
  (guard zelf ongewijzigd).
- Consistentie-controle tester.md <-> runner.sh-prompt: beide beschrijven dezelfde
  default-creds-uit-secret-flow, dezelfde namespace/PR-resolutie en dezelfde
  fallback.

AC-dekking (in-repo): AC1 ✓, AC2 ✓ (screenshots met test-user-data naar dropzone),
AC3 ✓ (alleen preview, geen prod-login), AC4 ✓ (wegwerp-fallback met melding),
AC5 ✓ (consistent + guard/robbert verwijderd). AC6 = runtime/operator-bewijs,
buiten de in-repo wijziging.
