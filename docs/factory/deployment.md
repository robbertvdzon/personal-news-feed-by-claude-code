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
  # PREVIEW_DB_BRANCH=pr-<N>. De tester leest die runtime (de claude-tester-SA
  # heeft per pnf-pr-* namespace secrets-read) en valideert ze fail-closed
  # met preview-db-guard.py vóór er ook maar iets gemuteerd wordt.
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
- De tester mag op die branch veilig muteren (bv. een wachtwoord-reset om in
  te loggen) zonder prod te raken.
- Bij PR-close ruimt de `preview-ns-labeller` de branch (incl. testdata) op.

Wiring (door `deploy/preview-ns-labeller/labeller.sh`):

1. Maakt de Neon-branch `pr-<N>` aan (parent = productie-branch).
2. Patcht `PNF_DATABASE_URL` in het `newsfeed-api-keys`-secret van
   `pnf-pr-<N>` naar de branch-URL, en zet de marker `PREVIEW_DB_BRANCH=pr-<N>`.
3. Maakt per `pnf-pr-*` namespace een Role/RoleBinding zodat de
   `claude-tester`-SA dat secret kan lezen (read-only, alleen daar).

Vereist dat de labeller-credentials aanwezig zijn (`NEON_API_KEY` +
`NEON_PROJECT_ID` in het secret). Ontbreken die, dan valt de labeller terug
op alleen namespace-labeling (geen branch, geen marker) en faalt de
tester-guard fail-closed — er worden dan geen DB-mutaties uitgevoerd.

**Tester-bedrading.** De tester krijgt zowel een bruikbare preview-URL
(`https://pnf-pr-<N>.vdzonsoftware.nl`) als een preview-branch-DB-connectie:
`runner.sh` leest de branch-URL runtime uit het `pnf-pr-<N>`-secret, valideert
ze met `preview-db-guard.py` en exporteert `PREVIEW_DB_URL` /
`PREVIEW_DB_BRANCH` / `PREVIEW_DB_GUARD`. Zie `docs/factory/agents/tester.md`.

Optioneel kan op de `jira-poller` de env-var `PROD_DB_HOST` (alléén hostname,
geen credentials) gezet worden; de guard weigert dan extra een URL die exact
op die prod-host wijst (defense-in-depth).

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
