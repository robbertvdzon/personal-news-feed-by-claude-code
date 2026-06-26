# Tester Instructions

- Lees `deployment.md` en `secrets-local.md`.
- Test de preview-omgeving via de URL-template uit `deployment.md`: `https://pnf-pr-{pr_num}.vdzonsoftware.nl`.
- Rapporteer bugs met concrete reproductiestappen en verwacht/werkelijk gedrag.
- Test zowel backend-endpoints (via de OpenAPI-spec) als de Flutter-frontend (via de browser op de preview-URL).
- Bij events-functionaliteit: controleer dat verwijderde events niet terugkomen na een nieuwe discovery-run.

## Preview vs productie: je raakt uitsluitend de preview

Elke preview draait op een **eigen, per-PR Neon-branch** (`pr-<PR_NUMBER>`)
— **niet** dezelfde PostgreSQL als productie. De `preview-ns-labeller`
maakt die branch aan en ruimt 'm (incl. alle testdata) bij PR-close weer
op. Je test **uitsluitend** de preview (`PREVIEW_URL` / de per-PR
branch-DB) en **nooit** productie. Verzin nooit een prod-login,
prod-credential of prod-DB-URL; de `claude-tester`-SA heeft sowieso geen
leesrechten op het prod-secret.

Doe **geen DB-mutaties**: de login gebeurt puur via de UI met een vaste
test-user uit het secret (zie hieronder). Geen wachtwoord-reset, geen
guard-check, geen afhankelijkheid van de `PREVIEW_DB_BRANCH`-marker.

## Inlog-modi

### Default: vaste test-user uit het secret

Voor **elke** visuele preview-test log je via de Flutter-UI in met een
vaste, dedicated test-user. Die user heeft testdata
(feeds/instellingen/historie) zodat je de gewijzigde feature realistisch
ziet — **zonder** DB-mutatie, wachtwoord-reset of guard-check.

1. **Bepaal de creds.** Zijn `TESTER_USERNAME` en `TESTER_PASSWORD` al
   gezet (de claude-runner heeft ze runtime read-only uit het secret
   gelezen), gebruik ze **ongewijzigd**. Zijn ze leeg (andere harness,
   `runner.sh` draaide niet), resolve ze dan **zelf**, read-only:
   - PR-nummer = `SF_PR_NUMBER` indien gezet, anders `PR_NUMBER`.
   - namespace = `SF_PREVIEW_NAMESPACE` indien gezet, anders
     `pnf-pr-<PR_NUMBER>`.
   - lees de creds uit het namespace-secret (waarden **niet** loggen):

     ```sh
     oc get secret newsfeed-api-keys -n <ns> \
       -o jsonpath='{.data.TESTER_USERNAME}' | base64 -d
     oc get secret newsfeed-api-keys -n <ns> \
       -o jsonpath='{.data.TESTER_PASSWORD}' | base64 -d
     ```

2. **Login via pure UI** op `PREVIEW_URL` met die creds (zelfde
   Flutter-UI-flow als bij de wegwerp-user; geen API-calls). Het account
   bestaat al → **niet registreren**. De test-user is permanent →
   **niet verwijderen**.

### Fallback: wegwerp-account (alleen bij ontbrekende creds)

Lukt het lezen van `TESTER_USERNAME`/`TESTER_PASSWORD` niet of zijn ze
leeg/onleesbaar, dan — en alléén dan — val je terug op de vaste
wegwerp-user `tester_<lowercase-STORY_ID>`: registreer/log in via de UI en
`DELETE /api/account/me` aan het eind zodat de DB schoon blijft.
**Meld expliciet** in je rapport dat je terugviel omdat de test-user-creds
ontbraken — nooit een wachtwoord-reset of zelf-verzonnen login.

Benoem in je rapport welke modus je koos en waarom (default = test-user
uit secret).

## Screenshots & dropzone (harness-afhankelijk)

De screenshot-dropzone hangt af van de actieve harness: de claude-runner
uploadt `/tmp/screenshots` als JIRA-attachment; de SF-`agent:local`-harness
verwacht `/work/screenshots`. Schrijf je screenshots — gemaakt met de
testdata van de test-user — naar de dropzone van de harness waarin je
draait.

Raakt de story de frontend, dan zijn browser-screenshots **verplicht
bewijs**. Kun je geen screenshots maken (Playwright/Chromium faalt, preview
niet live), eindig dan met een zichtbare `[blocker]` en `tested-fail` — nooit
stil `tested-ok` op basis van enkel code-inspectie.

> De operatieve, gezaghebbende werkwijze staat in de tester-system-prompt in
> `deploy/claude-runner/runner.sh`; dit document moet daarmee consistent
> blijven.
