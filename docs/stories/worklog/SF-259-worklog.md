# SF-259 - Worklog

Story-context bij eerste pickup:
Tester zelf-resolve preview-DB in tester.md + runner.sh-system-prompt

Maak de tester-instructies runner-agnostisch zodat ze niet meer afhangen van vooraf-geexporteerde PREVIEW_DB_URL/BRANCH/GUARD. Wijzig ALLEEN instructie-/prompt-tekst (geen code aan preview-db-guard.py, bash-resolutie, backend of Flutter).

1) docs/factory/agents/tester.md (secties 'Preview-DB' en 'Veiligheidsguard'): voeg een expliciete zelf-resolve-stap toe die geldt wanneer PREVIEW_DB_* leeg is: (a) bepaal namespace/PR robuust - gebruik SF_PREVIEW_NAMESPACE/SF_PR_NUMBER indien aanwezig, anders fallback pnf-pr-<PR_NUMBER>; (b) lees branch-creds uit het secret via 'oc get secret newsfeed-api-keys -n <ns> -o jsonpath=...' (PNF_DATABASE_URL, PREVIEW_DB_BRANCH); (c) draai preview-db-guard.py --emit-psql-url (pad /work/repo/deploy/claude-tester/preview-db-guard.py, onder claude-runner ook /usr/local/bin/preview-db-guard.py) en leid PREVIEW_DB_URL/BRANCH/GUARD daaruit af; (d) pas daarna de ONGEWIJZIGDE fail-closed guard toe.

2) De ingebedde tester-system-prompt in deploy/claude-runner/runner.sh (env-var-beschrijving ~480-492 + VEILIGHEIDSGUARD ~507-528): herformuleer naar 'PREVIEW_DB_* kunnen vooraf gezet zijn; zijn ze leeg → resolve zelf' met exact dezelfde stappen, woordelijk consistent met tester.md. De bestaande bash-resolutie (regels 189-226) blijft staan en mag hergebruikt worden wanneer de env al gezet is.

Behoud gedrag: guard blijft fail-closed (guard==ok + niet-lege URL + PREVIEW_DB_BRANCH==pr-<PR>); bij mislukte oc/secret/guard geen DB-mutatie en geen robbert-login (AC2); bij frontend-story zonder screenshots een zichtbare [blocker]/tested-fail, niet stil tested-ok (AC3); dropzone verwijst naar de actieve harness (/tmp/screenshots vs /work/screenshots). Voer aan het eind een review-stap uit op onderlinge consistentie tester.md <-> runner.sh (AC4) en dat de guard nergens verzwakt is. Codeconventie: geen inline comments.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `docs/factory/agents/tester.md`: nieuwe subsectie 'Preview-DB zélf resolven (env kan leeg zijn — runner-agnostisch)' in de Preview-DB-sectie. Beschrijft dat PREVIEW_DB_* niet gegarandeerd gezet is en geeft de 4 zelf-resolve-stappen (robuuste namespace/PR via SF_PREVIEW_NAMESPACE/SF_PR_NUMBER met fallback pnf-pr-<PR>; secret-read via oc get; preview-db-guard.py --emit-psql-url; daarna de ongewijzigde fail-closed guard). Veiligheidsguard-sectie verwijst nu naar 'env vooraf gezet óf zelf geresolved' en noemt mislukte oc/secret/guard expliciet als fail-closed. Nieuwe sectie 'Screenshots & dropzone' voor de harness-afhankelijke dropzone (/tmp/screenshots vs /work/screenshots) + verplichte [blocker]/tested-fail bij frontend zonder screenshots (AC3).
- `deploy/claude-runner/runner.sh` tester-system-prompt: env-var-beschrijving (PREVIEW_DB_URL/GUARD/BRANCH) geherformuleerd naar 'kunnen vooraf gezet zijn; leeg → zelf resolven'. In de VEILIGHEIDSGUARD een blok 'PREVIEW-DB ZELF RESOLVEN' toegevoegd met woordelijk dezelfde stappen als tester.md (a-d). De bestaande bash-resolutie (regels ~189-226) en preview-db-guard.py blijven ongewijzigd; de env-vars worden hergebruikt wanneer al gezet.
- Guard niet verzwakt: in beide bestanden blijft de fail-closed eis guard==ok + niet-lege URL + PREVIEW_DB_BRANCH==pr-<PR>. Geen wijziging aan preview-db-guard.py, bash-resolutie, backend of Flutter.

Tests:
- Geen code gewijzigd (enkel instructie-/prompt-tekst); geen nieuwe code om te unit-testen. Geverifieerd: `bash -n deploy/claude-runner/runner.sh` slaagt (heredoc intact) en `python3 -m unittest test_preview_db_guard` → 18 ok (guard-gedrag ongewijzigd). Consistentie-grep bevestigt dat beide bestanden de zelf-resolve-stappen + drie-voorwaarden-guard bevatten (AC4).
