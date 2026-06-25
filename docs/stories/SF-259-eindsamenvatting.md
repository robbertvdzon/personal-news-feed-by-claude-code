# SF-259 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Ik heb `.task.md`, het worklog (`SF-259-worklog.md`) en de review/test-comments gelezen. Hieronder de eindsamenvatting.

---

## Eindsamenvatting — SF-259: Tester resolvet preview-DB-guard zelf

### Wat is gebouwd
De tester-agent is **runner-agnostisch** gemaakt: hij hangt niet langer af van vooraf-geëxporteerde `PREVIEW_DB_URL` / `PREVIEW_DB_BRANCH` / `PREVIEW_DB_GUARD` env-vars. Robbert-login + screenshots werken nu ongeacht welke runner de tester startte (personal-feed's eigen `runner.sh` óf softwarefactory's `agent:local`-image, waar die env-vars leeg blijven).

Gewijzigd zijn **uitsluitend instructie-/prompt-teksten** (geen code):
- **`docs/factory/agents/tester.md`** — nieuwe subsectie *"Preview-DB zélf resolven (env kan leeg zijn)"*, een aangescherpte Veiligheidsguard-sectie en een nieuwe sectie *"Screenshots & dropzone"*.
- **De ingebedde tester-system-prompt in `deploy/claude-runner/runner.sh`** — env-var-beschrijving en VEILIGHEIDSGUARD geherformuleerd met woordelijk dezelfde zelf-resolve-stappen.

Beide bestanden beschrijven nu dezelfde 4 stappen wanneer `PREVIEW_DB_*` leeg is:
1. Namespace/PR robuust bepalen via `SF_PREVIEW_NAMESPACE` / `SF_PR_NUMBER`, met fallback op `pnf-pr-<PR_NUMBER>`.
2. Branch-creds read-only uit het secret lezen: `oc get secret newsfeed-api-keys -n <ns>` (`PNF_DATABASE_URL`, `PREVIEW_DB_BRANCH`).
3. `preview-db-guard.py --emit-psql-url` draaien en daaruit `PREVIEW_DB_URL/BRANCH/GUARD` afleiden.
4. De **ongewijzigde** fail-closed guard toepassen en daarna robbert-login + screenshots.

### Belangrijkste keuzes
- **Niets aan de code geraakt**: `preview-db-guard.py`, de bash-resolutie in `runner.sh` (regels ~189–226), backend (Kotlin/mvn) en Flutter blijven ongewijzigd. Bestaande bash-resolutie wordt hergebruikt wanneer de env al gezet is (claude-runner-pad); de instructies dekken expliciet het leeg-geval.
- **Guard niet verzwakt**: fail-closed eis blijft `guard==ok` + niet-lege URL + `PREVIEW_DB_BRANCH == pr-<PR>`. Mislukte `oc`/secret/guard → geen DB-mutatie, geen robbert-login.
- **Harness-afhankelijke dropzone** gedocumenteerd: claude-runner schrijft naar `/tmp/screenshots`, de SF-agent naar `/work/screenshots`.

### Wat is getest
- **AC4 (consistentie + geen env-afhankelijkheid):** beide bestanden beschrijven identieke zelf-resolve-stappen; oude aanname "runner zet env altijd vooraf" verwijderd. **PASS.**
- **AC2 (fail-closed):** guard-gedrag empirisch geverifieerd (prod-host → ABORT exit 3; marker afwezig → ABORT; geldige `pr-<N>`-host → OK + libpq-URL). De prompt-laag eist bovendien `PREVIEW_DB_BRANCH == pr-<PR>` bovenop guard.py. **PASS.**
- **AC3:** `[blocker]`/`tested-fail`-instructie aanwezig in beide bestanden; SF-259 raakt de frontend niet, dus n.v.t. op deze run.
- **Aanvullend:** `bash -n runner.sh` → OK (heredoc intact); `python3 -m unittest test_preview_db_guard` → 18 ok (guard-gedrag ongewijzigd). Guard-CLI in de instructies komt exact overeen met de echte argparse-interface.
- **Bijzonderheid:** de testrun draaide zélf onder de SF `agent:local`-harness met lege `PREVIEW_DB_*` en `SF_PR_NUMBER=133` — precies het doelscenario. `oc` was aanwezig met leesrecht en `preview-db-guard.py` bestond op het gedocumenteerde pad; de flow is dus live nagelopen en accuraat bevonden.

### Bewust niet gedaan
- **AC1/AC5 (end-to-end robbert-login + screenshots op een gezonde preview)** is **niet** in deze docs-PR aangetoond: `pnf-pr-133` is hier de factory-namespace zónder preview-marker, dus er was geen gezonde per-PR Neon-preview om login op te demonstreren. Conform de story-Aannames worden AC1/AC5 geverifieerd door de SF-tester op een lopende frontend-story — dit is geen losse code-deliverable in deze repo. **Open aandachtspunt voor de PO:** AC5 vereist nog een bevestigende run op een echte frontend-story.
- Geen wijziging aan code, runner-bash-resolutie, backend of Flutter; codeconventie "geen inline comments" gerespecteerd.

### Status
Developer, reviewer (akkoord) en tester (tested, geen bugs) zijn doorlopen. Wijziging is correct, intern consistent en scope-conform; de fail-closed guard is niet verzwakt.

```json
```
