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

## Review (SF-260, reviewer)
- [info] Diff t.o.v. `main` raakt enkel instructie-/prompt-tekst: `docs/factory/agents/tester.md` + de ingebedde tester-system-prompt in `deploy/claude-runner/runner.sh`. Geen wijziging aan `preview-db-guard.py`, bash-resolutie, backend of Flutter (conform scope).
- [info] Zelf-resolve-stappen (a-d) in beide bestanden zijn woordelijk consistent (AC4) en spiegelen de bestaande runner-bash (runner.sh:189-226). De guard-CLI in de instructies (`--url/--pr/--prod-host/--branch/--emit-psql-url`) komt exact overeen met de echte argparse-interface van `preview-db-guard.py`.
- [info] Fail-closed guard ongewijzigd/niet verzwakt: `guard==ok` + niet-lege URL + `PREVIEW_DB_BRANCH==pr-<PR>`; mislukte oc/secret/guard → expliciet fail (AC2). Frontend zonder screenshots → `[blocker]`/`tested-fail`, niet stil `tested-ok` (AC3). Dropzone harness-afhankelijk gedocumenteerd.
- [info] `bash -n runner.sh` slaagt (heredoc/escapes intact); geen JSON-artefacten in story-log/tester.md.
- Akkoord.

## Test (SF-261, tester)
Karakter van de wijziging: uitsluitend instructie-/prompt-tekst (`docs/factory/agents/tester.md` + de ingebedde tester-system-prompt in `deploy/claude-runner/runner.sh`). `git diff main...HEAD` raakt alleen die twee bestanden + dit worklog; geen wijziging buiten de `SYSTEM_PROMPT`-string van runner.sh (bash-resolutie ongewijzigd), geen wijziging aan `preview-db-guard.py`, backend of Flutter. Test = code-inspectie + interne consistentie + guard-gedrag (geen frontend-codewijziging om te screenshotten).

Bijzonderheid: deze run draait zélf onder de SF `agent:local`-harness met LEGE `PREVIEW_DB_URL`/`_GUARD`/`_BRANCH` en `SF_PR_NUMBER=133`/`SF_PREVIEW_NAMESPACE=pnf-pr-133` — precies het scenario dat de story moet ondersteunen. De nieuwe zelf-resolve-stappen zijn daarom live nagelopen.

Bevindingen per AC:
- AC4 (consistentie + geen env-afhankelijkheid): beide bestanden beschrijven dezelfde 4 zelf-resolve-stappen (robuuste namespace/PR via `SF_PREVIEW_NAMESPACE`/`SF_PR_NUMBER` met fallback `pnf-pr-<PR>`; `oc get secret newsfeed-api-keys` read-only; `preview-db-guard.py --emit-psql-url`; daarna de ongewijzigde fail-closed guard). De oude tekst die aannam dat de runner `PREVIEW_DB_*` altijd vooraf zet, is verwijderd; beide behandelen expliciet het leeg-geval. PASS.
- AC2 (fail-closed): guard-gedrag empirisch geverifieerd — `--prod-host == host` → ABORT (exit 3); marker afwezig + host zonder `pr-<N>` → ABORT (exit 3); geldige `pr-133`-host → OK + libpq-URL. De prompt-laag eist bovendien `PREVIEW_DB_BRANCH == pr-<PR>` bovenop guard.py (gelaagde verdediging). In deze live-namespace ontbreekt de `PREVIEW_DB_BRANCH`-marker en bevat de DB-host geen `pr-133` → zelf-resolve fail-closet correct: geen mutatie, geen robbert-login. PASS.
- AC1/AC5 (zelf-resolve t/m guard=ok + robbert-login + screenshots): vereist een echte frontend-story met gezonde per-PR Neon-preview. `oc` is aanwezig met leesrecht (`oc auth can-i` → yes) en `preview-db-guard.py` bestaat op het gedocumenteerde pad met werkende `--emit-psql-url`, dus de flow is volgbaar en accuraat; maar `pnf-pr-133` is hier de factory-namespace zonder preview-marker, dus geen gezonde preview om robbert-login op te demonstreren. Conform de story-Aannames worden AC1/AC5 geverifieerd door de SF-tester op een lopende frontend-story, niet in deze docs-PR. Niet-blokkerend voor deze story.
- AC3 (frontend zonder screenshots → `[blocker]`/`tested-fail`): instructietekst aanwezig in beide bestanden; SF-259 raakt de frontend niet, dus niet van toepassing op deze run.

Aanvullende verificaties (bestaande tooling, niets gewijzigd): `bash -n deploy/claude-runner/runner.sh` → OK (heredoc/escapes intact); `python3 -m unittest test_preview_db_guard` → 18 ok (guard-gedrag ongewijzigd). Guard-CLI in de instructies (`--url/--pr/--prod-host/--branch/--emit-psql-url`) komt exact overeen met de echte argparse-interface.

Conclusie: instructiewijziging is correct, intern consistent en volgbaar; fail-closed guard niet verzwakt; scope gerespecteerd. Geen bugs gevonden. → tested.
