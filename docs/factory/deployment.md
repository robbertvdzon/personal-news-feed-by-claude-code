---
default_base_branch: main
branch_prefix: ai/
preview_url_template: "https://pnf-pr-{pr_num}.vdzonsoftware.nl"
preview_namespace_template: "pnf-pr-{pr_num}"
preview_db_secret_recipe: |
  # Secrets worden automatisch gespiegeld via Reflector vanuit de
  # personal-news-feed namespace naar pnf-pr-* namespaces.
  # Geen handmatige stap nodig voor de tester.
---

# Deployment

Personal News Feed draait op een OpenShift-cluster via GitOps (ArgoCD).

## Flow

1. Push naar `main` → GitHub Actions bouwt nieuwe images → pushed naar `ghcr.io/robbertvdzon/personal-news-feed-{backend,frontend}:sha-…`
2. Workflow update SHA in `deploy/base/kustomization.yaml` → ArgoCD detecteert wijziging → pods rollen.
3. Geen handmatige stap nodig.

## Preview-deploys per PR

Elke open PR met `ai/`-prefix krijgt automatisch een preview op `https://pnf-pr-<N>.vdzonsoftware.nl`.

- Preview-images worden gebouwd bij elke `pull_request` event op GitHub Actions.
- **Let op:** alleen PR's die `newsfeedbackend/**` of `frontend/**` raken triggeren een image-build. Puur docs/deploy PR's krijgen geen preview-image.
- Alle previews praten met dezelfde Postgres als productie — migraties in een PR worden direct op prod-data toegepast.

## Secrets in het cluster

Secrets staan als SealedSecret in `deploy/base/sealed-secret-api-keys.yaml`. Aanpassen:

```bash
cp deploy/secrets-cluster.env.example deploy/secrets-cluster.env
# vul echte waarden in (dit bestand is gitignored)
./deploy/seal-secrets.sh
git add deploy/base/sealed-secret-api-keys.yaml
git commit && git push
```

## Namespace

Productie: `personal-news-feed` (OpenShift).

## Handige commando's

```bash
# ArgoCD-status
oc get application personal-news-feed -n openshift-gitops

# Pods
oc get pods -n personal-news-feed

# Logs
oc logs -n personal-news-feed deploy/backend -f
oc logs -n personal-news-feed deploy/frontend -f

# Routes
oc get routes -n personal-news-feed
```
