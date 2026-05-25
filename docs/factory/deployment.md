---
default_base_branch: main
branch_prefix: ai/
preview_url_template: "https://pnf-pr-{pr_num}.vdzonsoftware.nl"
preview_namespace_template: "pnf-pr-{pr_num}"
preview_db_secret_recipe: |
  echo "Secrets worden automatisch gespiegeld via Reflector naar elke pnf-pr-* namespace."
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

De preview deelt dezelfde PostgreSQL als productie — Flyway-migraties in een PR worden direct op prod-data toegepast.

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
