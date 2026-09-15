---
default_base_branch: main
branch_prefix: ai/
preview_url_template: "https://pnf-pr-{pr_num}.vdzonsoftware.nl"
preview_namespace_template: "pnf-pr-{pr_num}"
preview_db_secret_recipe: |
  echo "Database: preview-postgres in de eigen pnf-pr-<N>-namespace; TLS verify-full."
  echo "Geen productiecredentials of database-URL in prompts, logs of artifacts."
---

# Deployment — Personal News Feed

## Architectuur

```
GitHub Actions ── builds ──► ghcr.io (public)
        │
        ├── pusht nieuwe SHA naar deploy/base/kustomization.yaml
        ▼
ArgoCD ◄── synct main ── namespace: personal-news-feed (OpenShift)
        ├── backend   Pod + Service (poort 8080) + Route (debug)
        │              + PVC (runtime-state, 5 Gi)
        ├── frontend  Pod + Service + Route ← gebruikers (news.vdzonsoftware.nl)
        ├── reader    Pod + Service + Route ← reader.vdzonsoftware.nl
        ├── cloudflared    (tunnel: *.vdzonsoftware.nl → ingressrouter → Route)
        └── Secret (via SealedSecret in git)
```

Data staat in externe PostgreSQL (Neon); de podcast-audio staat sinds migratie
`V5__podcast_audio_bytes.sql` als BYTEA in de DB. Het PVC houdt alleen
runtime-state / admin-cleanup paden.

De prod-Neon-endpoint staat sinds SF-1739 op scale-to-zero: `suspend_timeout_seconds=300`
en max 1 CU, gezet met het idempotente `deploy/neon-endpoint-config.sh` (credentials
uitsluitend uit `NEON_API_KEY`/`NEON_PROJECT_ID`). Zie runbook §6.1 voor draaien,
read-only verifiëren (`--verify`), cold-startgedrag en terugdraaien.

## Productie-URL

`https://news.vdzonsoftware.nl` (via Cloudflare Tunnel → OpenShift-ingressrouter
→ frontend-Route)

## Routering — Host-based via de ingressrouter

Cloudflare stuurt de wildcard `*.vdzonsoftware.nl` via de tunnel naar de
OpenShift-ingressrouter van het cluster; die kiest op de (ongewijzigd
doorgegeven) `Host`-header de bijbehorende `Route`. Er zit géén
nginx-tussenlaag meer in het pad.

- De productiehosts staan declaratief in de manifests:
  `deploy/base/frontend-route.yaml` (`news.vdzonsoftware.nl`) en
  `deploy/base/reader-route.yaml` (`reader.vdzonsoftware.nl`).
- Op beide staat `insecureEdgeTerminationPolicy: Allow` (niet `Redirect`),
  omdat de Cloudflare-connector de router cluster-intern via HTTP bereikt — een
  redirect naar HTTPS zou dat verkeer laten stuiteren.
  `deploy/base/backend-route.yaml` (debug) houdt bewust `Redirect`.
- Voor previews zet de `preview`-overlay op de frontend-Route een
  placeholder-host, die de ApplicationSet per PR invult naar
  `pnf-pr-<N>.vdzonsoftware.nl`.

Details: `deploy/README.md` (sectie "Preview-deploys per PR", punt 5) en
`runbook.md` §7.

## Preview-deploys per PR

Elke open PR met branch-prefix `ai/` krijgt automatisch een preview op:

```
https://pnf-pr-<N>.vdzonsoftware.nl
```

### Preview-JWT — ephemeral sleutel per pod (NIET de prod-sleutel)

Previews krijgen sinds SF-1542 **niet** meer de productie-JWT-sleutel mee:
de `preview`-overlay (`deploy/overlays/preview/kustomization.yaml`) zet
`APP_JWT_SECRET` leeg en laat de `secretKeyRef` naar
`newsfeed-api-keys`/`JWT_SECRET` vervallen. De backend genereert dan bij het
opstarten zelf een random ephemeral sleutel. Gevolgen voor de factory:

- Tokens uit een preview zijn **alleen daar** geldig, niet op productie
  (en omgekeerd) — code op een PR-branch kan geen prod-token meer smeden.
- Tokens vervallen bij pod-herstart. De tester en de e2e-runner loggen per
  run opnieuw in via de UI / `POST /api/auth/login`, dus dat is geen
  regressie; zie je onverwacht een 401 na een redeploy, log dan opnieuw in.
- Productie (`openshift`-overlay) blijft de vaste sleutel uit de
  SealedSecret gebruiken; er is geen nieuw secret of her-sealen nodig.

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

## Deploy-flow (dagelijks gebruik)

Push naar `main`:
1. GitHub Actions bouwt nieuwe backend-, frontend- en reader-images (`ghcr.io/robbertvdzon/personal-news-feed-{backend,frontend,reader}:sha-…`).
2. Workflow committet de nieuwe SHA in `deploy/base/kustomization.yaml`.
3. ArgoCD detecteert de manifest-wijziging, pods rollen automatisch.

## Secret wijzigen

```bash
cp deploy/secrets-cluster.env.example deploy/secrets-cluster.env
# Vul echte waarden in (gitignored)
./deploy/seal-secrets.sh
git add deploy/base/sealed-secret-api-keys.yaml
git commit -m "deploy: update sealed secrets"
git push
```

## Status checken

```bash
oc get pods -n personal-news-feed
oc logs -n personal-news-feed deploy/backend -f
oc get routes -n personal-news-feed
```
