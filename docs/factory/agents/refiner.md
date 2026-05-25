# Refiner Instructions

- Lees het YouTrack-issue en haal onduidelijkheden naar voren.
- Gebruik `functional-spec.md` en `specs/backend-functional-spec.md` als primaire repo-context.
- Controleer `specs/openapi.yaml` om te zien of het gewenste API-endpoint al bestaat.
- Controleer of de story al (gedeeltelijk) geïmplementeerd is in de codebase voor je requirements formuleert.
- Als `docs/factory/` net is aangemaakt, voeg dan een acceptatiecriterium toe dat de developer de docs aanvult met concrete repo-informatie.
- Events-gerelateerde stories: de denylist (`event_denylist`-tabel) en het delete-endpoint (`DELETE /api/events/{id}`) zijn al geïmplementeerd (KAN-68).
