# SF-229 — Veilige preview-DB-bedrading + robbert-inlog/screenshot-flow voor tester

Story-log voor de PR (subtaak SF-237, developer).

## Doel

De tester-agent laten inloggen op de Flutter-web-preview met realistische
data (robbert/robbert) en screenshots achterlaten — veilig, uitsluitend
tegen de wegwerp-per-PR Neon-preview-branch, nooit tegen productie.

## Stappenplan

- [x] Issue + factory-docs lezen (deployment.md, technical-spec, runner.sh, poller.py, labeller.sh, rbac).
- [x] poller.py: PREVIEW_DB_URL niet meer uit prod-secret; optioneel PROD_DB_HOST doorgeven aan tester-Job (AC4).
- [x] preview-db-guard.py: fail-closed guard (pr-<N>-marker / prod-host-diff) + jdbc→libpq-conversie (AC2).
- [x] Unit-tests voor de guard (18 tests, AC2 dry-run-scenario's).
- [x] labeller.sh: PREVIEW_DB_BRANCH-marker in pnf-pr-<N>-secret + per-ns Role/RoleBinding voor claude-tester-SA (AC4).
- [x] labeller rbac.yaml: roles/rolebindings-write toevoegen voor de labeller-SA.
- [x] runner.sh: tester leest branch-DB runtime uit pnf-pr-<N>-secret, draait guard, exporteert PREVIEW_DB_URL/_BRANCH/_GUARD (AC2/AC4).
- [x] runner.sh tester-prompt: veiligheidsguard + robbert-reset/login-flow + inlog-modi (robbert vs wegwerp) (AC1/AC3).
- [x] claude-tester Dockerfile: guard-script COPY'en.
- [x] claude-tester rbac.yaml: TODO → geïmplementeerd-comment.
- [x] tester.md + deployment.md + preview_db_secret_recipe bijwerken (AC3/AC4).
- [x] Tests/syntax draaien (guard-unittests OK; bash -n OK; py_compile OK).

## Wat is gedaan en waarom

**Probleem.** De tester kreeg `PREVIEW_DB_URL` uit het prod-secret
(`newsfeed-api-keys/PNF_DATABASE_URL`); een schrijfactie zou prod raken.

**Oplossing (fail-closed).**

1. **Bron verlegd.** `poller.py` injecteert de prod-DB-URL niet meer als
   `PREVIEW_DB_URL`. In plaats daarvan leest `runner.sh` de per-PR
   Neon-branch-creds runtime uit het `pnf-pr-<N>`-secret (door de labeller
   gepatcht) en valideert ze. De tester-SA heeft géén leesrecht op het
   prod-secret → prod is fysiek onbereikbaar.

2. **Guard.** `deploy/claude-tester/preview-db-guard.py` bewijst fail-closed
   dat de doel-DB de `pr-<N>`-branch is (marker `PREVIEW_DB_BRANCH`, of
   `pr-<N>` in de URL, of host ≠ bekende prod-host) en weigert bij gelijkheid
   aan de prod-host / ontbrekende marker / twijfel. Met `--emit-psql-url`
   converteert 'ie de JDBC-URL uit het secret naar een psql-bruikbare
   libpq-URL. Gedekt door 18 unit-tests (incl. de AC2-dry-run tegen een
   prod-achtige URL → exit 3, geen mutatie).

3. **RBAC + marker.** `labeller.sh` zet nu de niet-gevoelige marker-key
   `PREVIEW_DB_BRANCH=pr-<N>` in het preview-secret en maakt per `pnf-pr-*`
   namespace een `claude-tester-secret-read` Role/RoleBinding (alleen
   secrets get/list). De labeller-ClusterRole kreeg daarvoor
   roles/rolebindings-write.

4. **Tester-prompt.** De system-prompt in `runner.sh` beschrijft de harde
   veiligheidsguard, de robbert-wachtwoord-reset (één gescopete
   `UPDATE ... WHERE username='robbert'` met `gen_salt('bf')` → `$2a`-bcrypt,
   compatibel met Spring's `BCryptPasswordEncoder`), de UI-login als
   robbert/robbert, en wanneer de robbert-flow geldt versus de wegwerp-
   `tester_<key>`-fallback. De bestaande screenshot-upload + safety-override
   blijven intact.

5. **Docs.** `tester.md`, `deployment.md` en `preview_db_secret_recipe`
   beschrijven nu per-PR Neon-branch i.p.v. "dezelfde PostgreSQL als
   productie", incl. hoe de tester aan preview-URL én branch-DB-URL komt.

## Niet lokaal te draaien

- `poller.py`-unittests vereisen `requests`/`flask`/`psycopg`; deze runner
  heeft geen `pip` om ze te installeren. `python3 -m py_compile` slaagt; CI
  valideert de tests. De wijziging is geïsoleerd (env-bedrading).
- Backend (mvn) en Flutter zijn niet geraakt — geen build nodig.
