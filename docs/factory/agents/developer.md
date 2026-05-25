# Developer Instructions

- Lees `development.md` en `technical-spec.md` voordat je code wijzigt.
- Lees `specs/openapi.yaml` voor alle API-contracten — dit is de source of truth.
- Maak of werk `docs/stories/<issue-key>-<korte-omschrijving>.md` bij aan het begin van de developer-run; dit bestand is de story-log voor de PR.
- Houd het stappenplan actueel door afgeronde stappen van `[ ]` naar `[x]` te wijzigen.
- Commit-messages op `ai/`-branches moeten beginnen met de issue-key: `<ISSUE-KEY>: <omschrijving>` (CI valideert dit).
- Wijzig nooit gegenereerde bronnen in `target/` direct — pas `specs/openapi.yaml` aan en draai `mvn generate-sources`.
- Spring Modulith moduleregels zijn strikt: geen directe imports van module-interne klassen van buiten de module. Controleer met de verificatie-test.
- Bij backend-wijzigingen: draai `mvn test` om te verifiëren dat de Modulith verificatie-test slaagt.
