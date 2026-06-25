# Tester Instructions

- Lees `deployment.md` en `secrets-local.md`.
- Test de preview-omgeving via de URL-template uit `deployment.md`: `https://pnf-pr-{pr_num}.vdzonsoftware.nl`.
- Rapporteer bugs met concrete reproductiestappen en verwacht/werkelijk gedrag.
- Test zowel backend-endpoints (via de OpenAPI-spec) als de Flutter-frontend (via de browser op de preview-URL).
- Bij events-functionaliteit: controleer dat verwijderde events niet terugkomen na een nieuwe discovery-run.

## Preview-DB: per-PR Neon-branch, gescheiden van productie

Sinds SF-229 draait elke preview op een **eigen, per-PR Neon-branch**
(`pr-<PR_NUMBER>`) — **niet** dezelfde PostgreSQL als productie. De
`preview-ns-labeller` maakt die branch aan, patcht de branch-DB-URL in het
`newsfeed-api-keys`-secret van de `pnf-pr-<N>` namespace, zet daar de
niet-gevoelige marker-key `PREVIEW_DB_BRANCH=pr-<N>`, en geeft de
`claude-tester`-SA secrets-read in diezelfde namespace. Bij PR-close wordt
de branch (incl. alle testdata) automatisch opgeruimd.

De runner (`deploy/claude-runner/runner.sh`) leest die branch-creds runtime
uit het `pnf-pr-<N>`-secret, valideert ze met `preview-db-guard.py`, en zet
`PREVIEW_DB_URL` (psql-bruikbaar), `PREVIEW_DB_BRANCH` en `PREVIEW_DB_GUARD`.
De tester-SA heeft **geen** leesrecht op het prod-secret — prod is fysiek
onbereikbaar.

### Veiligheidsguard (HARD, fail-closed)

Vóór **elke** DB-mutatie moet gelden:

- `PREVIEW_DB_GUARD == ok`, en
- `PREVIEW_DB_URL` is niet-leeg, en
- `PREVIEW_DB_BRANCH == pr-<PR_NUMBER>`.

Klopt één hiervan niet (host gelijk aan prod, marker afwezig, branching niet
actief, of welke twijfel dan ook) → **geen mutatie, geen robbert-login, geen
screenshots-op-robbert**; rapporteer `tested-fail` met een `[blocker]` dat de
preview-branch niet bevestigd kon worden, en stop. Gebruik nooit een zelf
samengestelde of prod-DB-URL.

## Inlog-modi

- **Robbert-flow (voorkeur bij realistische data).** Heeft de story
  bestaande feeds/instellingen/historie nodig om de wijziging zinvol te
  tonen, dan — en alléén als de guard `ok` is — reset je het wachtwoord van
  het robbert-account met één gescopete query en log je via de UI in als
  `robbert`/`robbert`:

  ```sql
  CREATE EXTENSION IF NOT EXISTS pgcrypto;
  UPDATE users SET password_hash = crypt('robbert', gen_salt('bf', 10))
   WHERE username = 'robbert';
  ```

  `gen_salt('bf')` geeft een `$2a$`-bcrypt-hash die Spring's
  `BCryptPasswordEncoder` accepteert. Raak geen andere rijen aan, verifieer
  dat exact 1 rij wijzigt, en verwijder robbert **niet** (de branch wordt
  toch weggegooid). Bestaat `robbert` niet of is het primaire account
  ambigu → niet gokken, niet resetten; val terug op de wegwerp-flow.

- **Wegwerp-flow (fallback).** Heeft de story geen bestaande data nodig (of
  faalde de guard maar kun je de feature ook zonder realistische data
  tonen), gebruik dan de vaste wegwerp-user `tester_<lowercase-STORY_ID>`:
  log in of registreer via de UI en `DELETE /api/account/me` aan het eind.

Benoem in je rapport welke modus je koos en waarom.

> De operatieve, gezaghebbende werkwijze staat in de tester-system-prompt in
> `deploy/claude-runner/runner.sh`; dit document moet daarmee consistent
> blijven.
