# SF-229 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

## Eindsamenvatting — SF-229: Veilige preview-DB-bedrading + robbert-inlog/screenshot-flow voor tester

### Wat is gebouwd
De tester-agent kan nu de Flutter-web-preview écht visueel testen met realistische data, **gegarandeerd alleen tegen de wegwerp-preview-DB en nooit tegen productie**.

- **Prod-DB losgekoppeld (AC4/AC5).** `poller.py` injecteert de prod-DB-URL niet langer als `PREVIEW_DB_URL`. In plaats daarvan leest `runner.sh` de per-PR Neon-branch-credentials runtime uit het `pnf-pr-<N>`-secret. De tester-SA heeft cluster-breed géén leesrecht op het prod-secret → productie is fysiek onbereikbaar.
- **Fail-closed veiligheidsguard (AC2).** Nieuw script `deploy/claude-tester/preview-db-guard.py` bewijst vóór elke mutatie dat de doel-DB de `pr-<N>`-branch is (marker `PREVIEW_DB_BRANCH`, `pr-<N>` in de URL, of host ≠ prod-host) en breekt af bij gelijkheid aan prod-host / ontbrekende marker / twijfel — geen mutatie, geen screenshots. Inclusief JDBC→libpq-URL-conversie. Gedekt door **18 unit-tests**.
- **RBAC + marker (AC4).** `labeller.sh` zet de niet-gevoelige marker `PREVIEW_DB_BRANCH=pr-<N>` in het preview-secret en maakt per `pnf-pr-*`-namespace een `claude-tester-secret-read` Role/RoleBinding (alleen secrets get/list). De labeller-ClusterRole kreeg de benodigde roles/rolebindings-write.
- **Robbert-reset + browser-test (AC1).** De tester-system-prompt beschrijft één gescopete `UPDATE users SET password_hash=… WHERE username='robbert'` (`gen_salt('bf')` → `$2a`-bcrypt, Spring `BCryptPasswordEncoder`-compatibel), UI-login als robbert/robbert via Playwright/Chromium, en screenshots naar `/tmp/screenshots/`. Bestaande upload + screenshot-safety-override blijven intact.
- **Docs (AC3/AC4).** `tester.md`, `deployment.md` en `preview_db_secret_recipe` beschrijven nu de per-PR Neon-branch (i.p.v. "dezelfde PostgreSQL als productie"), de guard, en wanneer de robbert-flow geldt versus de wegwerp-`tester_<key>`-fallback. `tester.md` verwijst naar `runner.sh` als gezaghebbend.

### Gemaakte keuzes
- **Bron-verlegging i.p.v. extra guard alleen:** prod-creds zijn niet meer leesbaar voor de tester; de guard is een tweede, fail-closed verdedigingslaag.
- **Username at-runtime bevestigd:** bij ontbrekende/ambigue `robbert`-user → afbreken, geen gok.
- **Primair guard-signaal** is de exacte `PREVIEW_DB_BRANCH`-marker uit het secret; host-diff en URL-substring zijn secundaire OR-paden.

### Getest
- 18 guard-unit-tests lokaal groen (incl. AC2-dry-run tegen prod-achtige URL → exit 3, geen mutatie); `bash -n` (labeller+runner) en `py_compile` (poller+guard) slagen.
- Reviewer (medium effort): **akkoord** op de volledige story-diff (12 bestanden, +752/−36); alle AC's afgevinkt; één niet-blokkerende info-opmerking over secundaire guard-paden.
- Story-brede test (SF-238): **test-approved**.

### Bewust niet gedaan
- `poller.py`-unittests niet lokaal gedraaid (geen `pip` voor `requests`/`flask`/`psycopg`) — CI valideert deze; wijziging is geïsoleerde env-bedrading.
- Geen prod-wijzigingen, geen Telegram-integratie (bestaat niet in de repo), geen functionele app-features — backend (mvn) en Flutter zijn niet geraakt.

### Aandachtspunt voor de PO
Vereiste cluster-credentials: de `preview-ns-labeller` heeft `NEON_API_KEY` + `NEON_PROJECT_ID` nodig voor actieve per-PR branching; controleer dat deze in de cluster aanwezig zijn vóór deploy.
