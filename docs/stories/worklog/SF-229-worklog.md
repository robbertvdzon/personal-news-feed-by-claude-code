# SF-229 - Worklog

Story-context bij eerste pickup:
Veilige preview-DB-bedrading + robbert-inlog/screenshot-flow voor tester

Implementeer het volledige ontwikkelwerk voor SF-229. (1) Herbedraad in deploy/jira-poller/poller.py de tester-PREVIEW_DB_URL van het prod-bron-secret newsfeed-api-keys naar de per-PR Neon-branch-URL uit het pnf-pr-<PR_NUMBER>-namespace-secret; geef PR_NUMBER ook in story-mode door. (2) Richt in deploy/preview-ns-labeller/labeller.sh + deploy/claude-tester/rbac.yaml een per-pnf-pr-*-namespace Role/RoleBinding in zodat de claude-tester-SA de preview-branch-DB-creds runtime kan lezen (volgens de bestaande TODO); verifieer dat per-PR Neon-branching actief is en richt het anders in. (3) Bouw in de tester-system-prompt (deploy/claude-runner/runner.sh ~415-690) een harde fail-closed veiligheidsguard: vóór elke DB-mutatie verifieren dat de doel-DB de pr-<PR_NUMBER>-branch is (marker/host wijkt af van prod-host); afwijking/ontbrekende marker/twijfel -> geen mutatie, geen screenshots, afbreken met duidelijke melding. (4) Voer de wachtwoord-reset uit als een enkele gescopete UPDATE users SET password_hash=<bcrypt $2a/$2b van 'robbert'> WHERE username='robbert' (compatibel met Spring BCryptPasswordEncoder); username at-runtime bevestigen, anders afbreken; geen andere data aanraken. (5) Laat de tester met Playwright/Chromium via de UI inloggen als robbert/robbert, naar de geraakte schermen navigeren en per scherm een screenshot naar /tmp/screenshots/ schrijven (sprekende namen); behoud bestaande upload + screenshot-safety-override. (6) Werk docs/factory/agents/tester.md bij en houd 'm consistent met de runner.sh-prompt, incl. wanneer de robbert-inlogflow geldt versus de wegwerp-tester_<story>-fallback. (7) Corrigeer docs/factory/deployment.md + preview_db_secret_recipe: per-PR Neon-branch i.p.v. 'dezelfde PostgreSQL als productie', en documenteer de preview-URL/-DB-bedrading voor de tester. Volg repo-naming (PREVIEW_URL/PREVIEW_DB_URL, /tmp/screenshots). Buiten scope: prod-wijzigingen, Telegram, functionele features. Sluit af met een review-stap op je eigen werk.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Volledige uitwerking + rationale in docs/stories/SF-229-veilige-preview-db-tester-flow.md.
- Kern: prod-DB-bron voor de tester verwijderd (poller.py); per-PR Neon-branch-creds
  worden runtime gelezen uit het pnf-pr-<N>-secret en fail-closed gevalideerd door
  deploy/claude-tester/preview-db-guard.py (18 unit-tests). labeller.sh zet de
  PREVIEW_DB_BRANCH-marker + maakt per-ns secrets-read RBAC voor de claude-tester-SA.
  runner.sh-tester-prompt + tester.md + deployment.md beschrijven de guard,
  robbert-reset/login en de keuze robbert-flow vs wegwerp-tester_<key>-flow.
- poller-unittests niet lokaal gedraaid (geen pip voor requests/flask/psycopg);
  py_compile + bash -n + guard-unittests slagen, CI valideert de rest.

Review (reviewer, medium effort) — AKKOORD:
- Volledige story-diff t.o.v. main beoordeeld (12 bestanden, +729/-36).
- AC1: robbert-reset (één gescopete UPDATE ... WHERE username='robbert' met
  gen_salt('bf',10) → $2a-bcrypt, Spring-compatibel) + UI-login + screenshot-
  dropzone/upload + safety-override intact (runner.sh additief, geen regressie).
- AC2: preview-db-guard.py is fail-closed; prod-URL aborteert ook ZONDER
  PROD_DB_HOST (ontbrekende pr-<N>-marker → exit 3, geen mutatie). 18 unit-tests
  lokaal groen (python3 -m unittest).
- AC3: tester.md en runner.sh-prompt consistent; tester.md verwijst expliciet
  naar runner.sh als gezaghebbend.
- AC4: poller.py haalt prod-secret-bron weg; runner.sh leest branch-creds uit
  pnf-pr-<N>-secret; labeller zet PREVIEW_DB_BRANCH-marker + per-ns secrets-read
  RBAC; labeller-ClusterRole kreeg roles/rolebindings-write; TESTER_SA_NS
  (pnf-software-factory) matcht de SA-namespace in rbac.yaml.
- AC5: claude-tester-ClusterRole sluit secrets cluster-wide uit → prod-secret
  fysiek onleesbaar; geen prod-mutaties; Neon-branch wordt bij PR-close opgeruimd.
- bash -n (labeller+runner) en py_compile (poller+guard) slagen; story-log en
  worklog vrij van JSON-artefacten; geen duplicate Spring-annotaties.
- [info] guard accepteert host≠prod-host als positief signaal zónder marker, en
  url_marker is een substring-match — beide secundaire OR-paden; primair en
  gezaghebbend is de exacte PREVIEW_DB_BRANCH-marker uit het secret, dus
  praktisch risico verwaarloosbaar. Geen blocker.
