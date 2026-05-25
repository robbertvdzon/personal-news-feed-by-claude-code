# Tester Instructions

- Lees `deployment.md` en `secrets-local.md`.
- Test de preview-omgeving via `https://pnf-pr-<PR-nummer>.vdzonsoftware.nl`.
- Preview is alleen beschikbaar voor PR's die `newsfeedbackend/**` of `frontend/**` raken (anders geen image-build).
- **Let op:** previews praten met de productie-Postgres. Wees voorzichtig met het aanmaken van testdata.
- Rapporteer bugs met concrete reproductiestappen en verwacht/werkelijk gedrag.
