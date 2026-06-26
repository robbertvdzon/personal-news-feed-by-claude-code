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

### Preview-DB zélf resolven (env kan leeg zijn — runner-agnostisch)

`PREVIEW_DB_URL` / `PREVIEW_DB_BRANCH` / `PREVIEW_DB_GUARD` zijn **niet
gegarandeerd vooraf gezet**. Draaide je onder de claude-runner
(`deploy/claude-runner/runner.sh`), dan heeft die ze runtime uit het secret
gelezen, met `preview-db-guard.py` gevalideerd en geëxporteerd — **hergebruik
ze dan ongewijzigd**. Draaide je onder een andere harness (bv.
softwarefactory's `agent:local`-image met eigen entrypoint, waar `runner.sh`
nooit draait), dan blijven die env-vars **leeg**. Zijn ze leeg, dan resolve je
de preview-DB **zelf**, met exact dezelfde stappen die de runner anders zou
doen:

1. **Bepaal namespace + PR robuust.** PR-nummer = `SF_PR_NUMBER` indien gezet,
   anders `PR_NUMBER`. Namespace = `SF_PREVIEW_NAMESPACE` indien gezet, anders
   de bestaande conventie `pnf-pr-<PR_NUMBER>`.
2. **Lees de branch-creds uit het namespace-secret** (read-only):

   ```sh
   oc get secret newsfeed-api-keys -n <ns> \
     -o jsonpath='{.data.PNF_DATABASE_URL}' | base64 -d
   oc get secret newsfeed-api-keys -n <ns> \
     -o jsonpath='{.data.PREVIEW_DB_BRANCH}' | base64 -d
   ```

   Geen secret leesbaar (Neon-branching niet actief, geen RoleBinding) →
   guard blijft `fail`: geen DB-mutatie, geen robbert-login.
3. **Draai de bestaande guard met `--emit-psql-url`** (geen wijziging aan dat
   script). Pad: `/work/repo/deploy/claude-tester/preview-db-guard.py`, onder
   de claude-runner ook `/usr/local/bin/preview-db-guard.py`:

   ```sh
   python3 <guard-pad> --url "<PNF_DATABASE_URL>" --pr "<PR_NUMBER>" \
     --prod-host "${PROD_DB_HOST:-}" --branch "<PREVIEW_DB_BRANCH>" \
     --emit-psql-url
   ```

   Exit 0 → de guard print een psql-bruikbare libpq-URL; leid daaruit af:
   `PREVIEW_DB_URL` = die URL, `PREVIEW_DB_BRANCH` = de marker uit het secret,
   `PREVIEW_DB_GUARD = ok`. Niet-0 exit → `PREVIEW_DB_GUARD = fail`,
   `PREVIEW_DB_URL` leeg.
4. **Pas daarna de ongewijzigde fail-closed guard hieronder toe** en doe pas
   bij `ok` robbert-login + screenshots.

De tester-SA heeft **geen** leesrecht op het prod-secret — prod is fysiek
onbereikbaar.

### Veiligheidsguard (HARD, fail-closed)

Vóór **elke** DB-mutatie moet gelden (of de env nu vooraf gezet was of door
jou zelf is geresolved):

- `PREVIEW_DB_GUARD == ok`, en
- `PREVIEW_DB_URL` is niet-leeg, en
- `PREVIEW_DB_BRANCH == pr-<PR_NUMBER>`.

Klopt één hiervan niet (host gelijk aan prod, marker afwezig, branching niet
actief, oc/secret/guard mislukt, of welke twijfel dan ook) → **geen mutatie,
geen robbert-login, geen screenshots-op-robbert**; rapporteer `tested-fail`
met een `[blocker]` dat de preview-branch niet bevestigd kon worden, en stop.
Gebruik nooit een zelf samengestelde of prod-DB-URL.

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

## Screenshots & dropzone (harness-afhankelijk)

De screenshot-dropzone hangt af van de actieve harness: de claude-runner
uploadt `/tmp/screenshots` als JIRA-attachment; de SF-`agent:local`-harness
verwacht `/work/screenshots`. Schrijf je screenshots naar de dropzone van de
harness waarin je draait.

Raakt de story de frontend, dan zijn browser-screenshots **verplicht
bewijs**. Kun je geen screenshots maken (Playwright/Chromium faalt, preview
niet live), eindig dan met een zichtbare `[blocker]` en `tested-fail` — nooit
stil `tested-ok` op basis van enkel code-inspectie.

> De operatieve, gezaghebbende werkwijze staat in de tester-system-prompt in
> `deploy/claude-runner/runner.sh`; dit document moet daarmee consistent
> blijven.
