---
default_base_branch: main
branch_prefix: ai/
preview_url_template: "https://pnf-pr-{pr_num}.vdzonsoftware.nl"
preview_namespace_template: "pnf-pr-{pr_num}"
preview_db_secret_recipe: |
  # De preview-namespace krijgt de DB-secret automatisch via Kubernetes Reflector
  # (gespiegeld vanuit de `personal-news-feed` productie-namespace).
  # Geen handmatige actie nodig.
---

# Deployment

## Productie

De app draait op OpenShift, beheerd via ArgoCD. Kustomize-overlays:

- `deploy/overlays/openshift/` — productie
- `deploy/overlays/preview/` — feature-branch previews

## Preview-omgevingen

Elke PR krijgt automatisch een preview-namespace `pnf-pr-<pr_num>` met:

- URL: `https://pnf-pr-<pr_num>.vdzonsoftware.nl`
- Backend + frontend worden gedeployd via de ArgoCD ApplicationSet
- Secrets worden automatisch gespiegeld via Kubernetes Reflector
- Geen persistente PVC (audio via emptyDir — throwaway)

## Image-tags

CI (GitHub Actions) bouwt Docker-images en pusht ze met de commit-SHA als tag.
De ApplicationSet patcht de namespace en image-tag per PR in.

## DNS / ingress

Cloudflare Tunnel + wildcard hostname `*.vdzonsoftware.nl`.
De preview-router loopt in de `personal-news-feed`-namespace en routeert op
hostname naar de juiste preview-namespace.
