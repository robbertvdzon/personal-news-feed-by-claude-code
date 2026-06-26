# Factory Docs — Personal News Feed

Een zelf-gehoste persoonlijke nieuwsfeed met AI-curation, podcastgeneratie en multi-user ondersteuning.

**Stack:** Spring Boot 4 / Kotlin 2 backend (Maven) · Flutter frontend · PostgreSQL (Neon) · Flyway · Spring Modulith · OpenShift + ArgoCD.

## Index

- `development.md`: lokaal bouwen, testen en ontwikkelconventies.
- `functional-spec.md`: functionele afspraken, gebruikersflows en acceptatiecriteria.
- `technical-spec.md`: technische keuzes, frameworks, codeconventies en modulestructuur.
- `deployment.md`: deploy-flow, preview-omgevingen en factory-config.
- `secrets-local.md`: lokale secrets en omgevingsvariabelen.
- `agents/`: rol-specifieke instructies voor factory-agents.

## Eerste leeslijst voor agents

1. `docs/factory/technical-spec.md` — Spring Modulith-moduleregels, lagenstructuur, Maven-root
2. `docs/factory/functional-spec.md` — gedrag van de app inclusief events en denylist
3. `docs/factory/development.md` — build- en testcommando's
4. `specs/backend-technical-spec.md` — uitgebreide backend-architectuur (gezaghebbend)
5. `specs/openapi.yaml` — API-contract (source of truth)
