# Developer Instructions

- Maak of werk `docs/stories/<issue-key>-<korte-omschrijving>.md` bij aan het begin van de developer-run; dit bestand is de story-log voor de PR.
- Houd het stappenplan actueel door afgeronde stappen van `[ ]` naar `[x]` te wijzigen.
- Leg onder het stappenplan kort vast wat je hebt gedaan en waarom.
- Lees `development.md` en `technical-spec.md` voordat je code wijzigt.
- Lees ook `specs/backend-technical-spec.md` en `specs/openapi.yaml` — dit zijn de gezaghebbende bronnen.
- Spring Modulith-moduleregels kunnen worden afgedwongen met een `ModuleStructureTest` (`ApplicationModules…verify()`); die test bestaat nog niet in de repo (zie `specs/backend-technical-spec.md` §7 en `development.md`). Voeg er een toe en draai die als je modulegrenzen wijzigt; verifieer wijzigingen anders met `mvn test`.
- Flyway-migraties toevoegen als `V{n+1}__beschrijving.sql`; nooit een bestaande migratie aanpassen.
- Laat geen raw JSON-artefacten (`agent_tips_update`, `phase`) achter in story-bestanden — verwijder vóór commit.
- Controleer na elk `Write`-tool-gebruik met `tail -20` dat het bestand eindigt waar verwacht.
