---
default_base_branch: main
branch_prefix: ai/
preview_url_template: "https://pnf-pr-{pr_num}.vdzonsoftware.nl"
preview_namespace_template: "pnf-pr-{pr_num}"
preview_db_secret_recipe: |
  # Previews draaien op een EIGEN per-PR Neon-branch (pr-<N>), niet op prod.
  # Het basis-secret wordt via Reflector naar elke pnf-pr-* namespace
  # gespiegeld; de preview-ns-labeller patcht daarin vervolgens
  # PNF_DATABASE_URL naar de branch-specifieke URL en zet de marker-key
  # PREVIEW_DB_BRANCH=pr-<N> (die marker voedt nog steeds de geïsoleerde
  # per-PR branch-DB, maar wordt sinds SF-282 niet meer door de
  # tester-login gebruikt). De tester logt in met een vaste test-user uit
  # hetzelfde secret (TESTER_USERNAME/TESTER_PASSWORD) — read-only, geen
  # DB-mutatie en geen guard-check meer. De claude-tester-SA heeft per
  # pnf-pr-* namespace secrets-read.
  echo "Test-user:            oc get secret newsfeed-api-keys -n pnf-pr-<N> -o jsonpath='{.data.TESTER_USERNAME}' | base64 -d"
  echo "Preview-branch-DB-URL: oc get secret newsfeed-api-keys -n pnf-pr-<N> -o jsonpath='{.data.PNF_DATABASE_URL}' | base64 -d"
  echo "Branch-marker:        oc get secret newsfeed-api-keys -n pnf-pr-<N> -o jsonpath='{.data.PREVIEW_DB_BRANCH}' | base64 -d"
---

# Deployment — Personal News Feed

## Architectuur

```
GitHub Actions ── builds ──► ghcr.io (public)
        │
        ├── pusht nieuwe SHA naar deploy/base/kustomization.yaml
        ▼
ArgoCD ◄── synct main ── namespace: personal-news-feed (OpenShift)
        ├── backend Pod + Service (poort 8080)
        ├── frontend Pod + Service
        ├── PVC (audio-MP3, 5 Gi)
        └── Secret (via SealedSecret in git)
```

Data staat in externe PostgreSQL (Neon); audio-MP3's staan als BYTEA in de DB.

## Productie-URL

`https://news.vdzonsoftware.nl` (via Cloudflare Tunnel → OpenShift-frontend)

## Preview-deploys per PR

Elke open PR met branch-prefix `ai/` krijgt automatisch een preview op:

```
https://pnf-pr-<N>.vdzonsoftware.nl
```

### Preview-DB — eigen per-PR Neon-branch (NIET prod)

Elke preview krijgt een **eigen, wegwerp-Neon-branch** `pr-<N>`, afgesplitst
van de productie-branch. Dat betekent:

- Flyway-migraties in een PR draaien op de **branch**, niet op prod-data.
- De branch levert de geïsoleerde testdata waarmee de tester de feature
  realistisch ziet. De tester muteert die branch niet meer: inloggen gaat
  via een vaste test-user uit het secret (zie "Tester-login" hieronder).
- Bij PR-close ruimt de `preview-ns-labeller` de branch (incl. testdata) op.

Wiring (door `deploy/preview-ns-labeller/labeller.sh`):

1. Maakt de Neon-branch `pr-<N>` aan (parent = productie-branch).
2. Patcht `PNF_DATABASE_URL` in het `newsfeed-api-keys`-secret van
   `pnf-pr-<N>` naar de branch-URL, en zet de marker `PREVIEW_DB_BRANCH=pr-<N>`.
3. Maakt per `pnf-pr-*` namespace een Role/RoleBinding zodat de
   `claude-tester`-SA dat secret kan lezen (read-only, alleen daar).

Vereist dat de labeller-credentials aanwezig zijn (`NEON_API_KEY` +
`NEON_PROJECT_ID` in het secret). Ontbreken die, dan valt de labeller terug
op alleen namespace-labeling (geen branch, geen marker); de preview deelt dan
geen geïsoleerde branch-DB. De tester-login zelf raakt de DB niet en blijft
ongewijzigd werken via de test-user-creds (zie "Tester-login" hieronder).

**Tester-login (vaste test-user, sinds SF-282).** De tester krijgt een
bruikbare preview-URL (`https://pnf-pr-<N>.vdzonsoftware.nl`) en logt daarop
via de Flutter-UI in met een vaste, dedicated test-user. De creds
`TESTER_USERNAME` / `TESTER_PASSWORD` staan in het `newsfeed-api-keys`-secret
(via Reflector in elke `pnf-pr-*`-namespace beschikbaar). Draait de tester
onder de claude-runner, dan leest `runner.sh` ze runtime read-only uit het
secret en exporteert `TESTER_USERNAME` / `TESTER_PASSWORD`. Draait de tester
onder een andere harness (bv. softwarefactory's `agent:local`-image, waar
`runner.sh` nooit draait), dan blijven die env-vars leeg en **leest de tester
ze zélf** read-only uit het namespace-secret (namespace/PR uit
`SF_PREVIEW_NAMESPACE` / `SF_PR_NUMBER` met fallback op `pnf-pr-<N>`). De login
doet **geen DB-mutatie, geen wachtwoord-reset en geen guard-check**. Ontbreken
of falen de creds, dan valt de tester terug op de wegwerp-account-flow
(`tester_<story-id>` registreren via de UI + `DELETE /api/account/me` aan het
eind). Zie `docs/factory/agents/tester.md`.

> De oude SF-229-flow (robbert-wachtwoord-reset + fail-closed
> `PREVIEW_DB_GUARD`-check) is hiermee vervallen. De per-PR Neon-branch en
> `preview-db-guard.py` zelf blijven ongewijzigd bestaan (geïsoleerde testdata
> per PR), maar zijn niet meer onderdeel van de tester-login. De optionele
> `PROD_DB_HOST`-env-var op de `jira-poller` voedde die guard en is daarmee
> legacy: ze speelt geen rol meer in de loginflow.

## Deploy-flow (dagelijks gebruik)

Push naar `main`:
1. GitHub Actions bouwt nieuwe backend- en frontend-images (`ghcr.io/robbertvdzon/personal-news-feed-{backend,frontend}:sha-…`).
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
