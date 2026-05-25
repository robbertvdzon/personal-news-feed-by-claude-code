# Tester Instructions

- Lees `deployment.md` en `secrets-local.md`.
- Test de preview-omgeving via `https://pnf-pr-<pr_num>.vdzonsoftware.nl`.
- Rapporteer bugs met concrete reproductiestappen en verwacht/werkelijk gedrag.
- Bij event-gerelateerde stories: controleer het prullenbak-icoon in
  `EventDetailScreen`, verifieer dat het event verdwijnt uit de lijst,
  en dat een volgende discovery-run het event niet terugplaatst.
- Backend-endpoints zijn beschikbaar op dezelfde preview-URL (path `/api/…`).
