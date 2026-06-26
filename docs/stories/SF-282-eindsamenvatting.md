# SF-282 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Ik heb `.task.md`, het worklog (`SF-282-worklog.md`) met de developer- en reviewer-secties, en de relevante factory-docs gelezen. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-282: Tester logt in met een vaste test-user uit het secret

### Wat is gebouwd
De tester-loginflow voor visuele preview-tests is herschreven. De **default-inlogwijze** is nu een **vaste, dedicated test-user** waarvan de credentials read-only uit een secret komen — de oude robbert-wachtwoord-reset + fail-closed guard zijn vervallen.

De wijziging is **puur documentair/instructief** (agent-prompts) — geen applicatiecode. Twee bestanden zijn aangepast:

- **`deploy/claude-runner/runner.sh`** (gezaghebbende bron):
  - *Bash-tak (~160–226):* de SF-229 preview-branch-DB-resolutie, de `preview-db-guard.py`-aanroep en de `PREVIEW_DB_URL/BRANCH/GUARD`-exports zijn verwijderd. Daarvoor in de plaats: read-only inlezen van `TESTER_USERNAME`/`TESTER_PASSWORD` uit het secret `newsfeed-api-keys` van de namespace `${SF_PREVIEW_NAMESPACE:-pnf-pr-<PR>}`. De wachtwoordwaarde wordt nooit gelogd (alleen aan-/afwezigheid).
  - *Tester-system-prompt (~456–760):* de `VEILIGHEIDSGUARD`-sectie en de robbert-reset-inlogmodus zijn vervangen door (1) een "PREVIEW vs PRODUCTIE"-sectie en (2) een INLOG-MODI-sectie met de test-user-uit-secret als DEFAULT en de wegwerp-user als laatste fallback. Het Playwright-voorbeeldscript vertakt nu op `usingTestUser`.
- **`docs/factory/agents/tester.md`**: volledig herschreven en één-op-één consistent gemaakt met de runner.sh-prompt (zelfde default/fallback, secret-keys, namespace-resolutie en preview-only-regel).

### Belangrijkste keuzes
- **Test-user uit secret als DEFAULT**: geen DB-mutatie, geen wachtwoord-reset, geen guard-check en geen afhankelijkheid van de `PREVIEW_DB_BRANCH`-marker meer.
- **Wegwerp-account-flow behouden, maar enkel als laatste fallback** (`tester_<lowercase-STORY_ID>` registreren + `DELETE /api/account/me`) wanneer de creds ontbreken/onleesbaar zijn — mét expliciete melding door de tester.
- **Runner-agnostisch**: namespace/PR worden resolved via `${SF_PREVIEW_NAMESPACE:-pnf-pr-<PR>}` / `${SF_PR_NUMBER:-PR_NUMBER}`, zonder vooraf-gezette `SF_`-env-vars te veronderstellen.
- **Letterlijke key-namen** `TESTER_USERNAME`/`TESTER_PASSWORD` (zonder `PNF_`-prefix), conform de story.
- **Niet aangeraakt**: `deploy/claude-tester/preview-db-guard.py` en de `preview-ns-labeller`-branch-creatie blijven ongewijzigd — alleen de guard-/marker-afhankelijkheid in de loginflow vervalt.

### Wat is getest
- `bash -n deploy/claude-runner/runner.sh` → **OK** (prompt-string met escapes parseert correct).
- `python3 -m unittest test_preview_db_guard` → **18 tests OK** (guard zelf ongewijzigd).
- Consistentie-controle `tester.md` ↔ runner.sh-prompt: beide beschrijven dezelfde default-creds-uit-secret-flow, namespace/PR-resolutie en fallback.
- Reviewer-akkoord: geen blockers/bugs; resterende `PREVIEW_DB_*`/robbert-vermeldingen zijn enkel deprecatie-/negatie-tekst, conform scope.
- **AC-dekking in-repo**: AC1 ✓, AC2 ✓ (screenshots met test-user-data naar dropzone), AC3 ✓ (alleen preview, geen prod-login), AC4 ✓ (wegwerp-fallback met melding), AC5 ✓ (consistent, guard/robbert verwijderd).

### Bewust / nog niet gedaan
- **AC6 (end-to-end live bewijs)** valt buiten de in-repo wijziging: dit vereist operator-prerequisites (een dedicated test-user mét testdata in productie + `TESTER_USERNAME`/`TESTER_PASSWORD` toevoegen aan het sealed secret `newsfeed-api-keys`) en een live preview. Wordt op runtime aangetoond.
- **Geen secret-waarden** in de repo gecommit; de agent maakt de test-user en secret-keys niet aan.
- **Telegram-weergave** is gedrag van de externe Software-Factory-harness en valt buiten deze repo; screenshot-upload gaat hier naar YouTrack/JIRA-attachments.

---
