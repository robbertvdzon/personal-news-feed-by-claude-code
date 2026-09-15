# Tester Instructions

- Lees `deployment.md` en `secrets-local.md`.
- Test de preview-omgeving via de URL-template uit `deployment.md`: `https://pnf-pr-{pr_num}.vdzonsoftware.nl`.
- Rapporteer bugs met concrete reproductiestappen en verwacht/werkelijk gedrag.
- Test zowel backend-endpoints (via de OpenAPI-spec) als de Flutter-frontend (via de browser op de preview-URL).

### Preview-database op de gedeelde non-productionserver

Sinds 15 september 2026 krijgt iedere preview een lege database
`pnf_pr_<nummer>_<namespace-uid-hash>` op `postgres.postgres-nonproduction.svc`.
De controller in `robberts-infrastructure` maakt de rol en het Secret
`preview-postgres` aan. De backend wacht op dat Secret en gebruikt TLS met
`verify-full`. Flyway maakt het schema; er wordt geen productiedata gekopieerd.

De ApplicationSet maakt de namespace. De bestaande preview-reconciler verwijdert
haar na de PR-lifecyclecontrole. De databasecontroller verwijdert vervolgens de
bij die namespace-UID behorende database na een uur grace. Een heropende PR met
een nieuwe namespace krijgt een andere database en een ander wachtwoord.

`newsfeed-api-keys` blijft uitsluitend in `personal-news-feed`. De oude Neon-labeller
is uitgeschakeld en heeft geen secretrechten. Preview-AI-verkeer gebruikt de
Agent Runtime-acceptatieomgeving met een eigen, daarvoor gescopete credential.
Productie blijft op Neon.

### Tester-login en testdata

Test uitsluitend de preview-URL. Gebruik de bestaande preview-AI-toegang als de
harness die veilig aanbiedt, of registreer via de UI een wegwerpaccount
`tester_<lowercase-story-id>`. Maak benodigde synthetische data via de preview-UI
of de daarvoor bedoelde preview-API. Verwijder het wegwerpaccount na de test.
Er bestaat geen uit productie gekopieerde testgebruiker en er is geen
`TESTER_USERNAME`/`TESTER_PASSWORD` uit `newsfeed-api-keys` nodig. Geen
productielogins, productiecredentials, rechtstreekse wachtwoordresets of SQL-datawijzigingen.

## Screenshots & dropzone (harness-afhankelijk)

De screenshot-dropzone hangt af van de actieve harness: de claude-runner
uploadt `/tmp/screenshots` als JIRA-attachment; de SF-`agent:local`-harness
verwacht `/work/screenshots`. Schrijf je screenshots — gemaakt met synthetische
testdata — naar de dropzone van de harness waarin je
draait.

Raakt de story de frontend, dan zijn browser-screenshots **verplicht
bewijs**. Kun je geen screenshots maken (Playwright/Chromium faalt, preview
niet live), eindig dan met een zichtbare `[blocker]` en `tested-fail` — nooit
stil `tested-ok` op basis van enkel code-inspectie.


- Lockfile-discipline: wijzig `frontend/pubspec.lock` (of andere lockfiles) alleen als de
  bijbehorende manifest (`pubspec.yaml`) ook wijzigt. Een kale lockfile-drift is een bijproduct
  van `flutter pub get` en wordt door de factory automatisch meegecommit — zet 'm daarom vóór je
  handover terug (`git checkout -- frontend/pubspec.lock`), tenzij de bump het expliciete doel is
  en je 'm in je handover/worklog verantwoordt. (Dit kostte SF-987 een volledige reviewronde.)
