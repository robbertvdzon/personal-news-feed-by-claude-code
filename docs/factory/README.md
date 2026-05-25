# Factory Docs

Personal News Feed — een AI-gestuurde nieuwsfeed-app die voor jou nieuws, podcasts
en tech-events bewaakt en samenvat.

## Onderdelen

| Map/bestand | Doel |
|---|---|
| `newsfeedbackend/` | Spring Boot 4/Kotlin 2 backend (Maven), REST API + scheduled pipelines |
| `frontend/` | Flutter-app (web + Android), communiceert met de backend via JWT |
| `deploy/` | Kubernetes manifests, ArgoCD ApplicationSet, OpenShift overlays |
| `e2e/` | End-to-end tests |
| `specs-v2/` | Functionele specificaties |

## Index

- `development.md` — lokaal bouwen, testen en ontwikkelconventies
- `functional-spec.md` — functionele afspraken en gebruikersgedrag
- `technical-spec.md` — technische keuzes, frameworks en codeconventies
- `deployment.md` — deploy-flow en machine-leesbare factory-config
- `secrets-local.md` — lokale secrets en waar die vandaan komen
- `agents/` — rol-specifieke instructies voor factory-agents
