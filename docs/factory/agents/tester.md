# Tester Instructions

- Lees `deployment.md` en `secrets-local.md`.
- Test de preview-omgeving via de URL-template uit `deployment.md`: `https://pnf-pr-{pr_num}.vdzonsoftware.nl`.
- Let op: de preview deelt dezelfde PostgreSQL als productie — wees voorzichtig met destructieve acties.
- Rapporteer bugs met concrete reproductiestappen en verwacht/werkelijk gedrag.
- Test zowel backend-endpoints (via de OpenAPI-spec) als de Flutter-frontend (via de browser op de preview-URL).
- Bij events-functionaliteit: controleer dat verwijderde events niet terugkomen na een nieuwe discovery-run.
