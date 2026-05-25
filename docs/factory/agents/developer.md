# Developer Instructions

- Maak of werk `docs/stories/<issue-key>-<korte-omschrijving>.md` bij aan het
  begin van de developer-run; dit bestand is de story-log voor de PR.
- Houd het stappenplan actueel door afgeronde stappen van `[ ]` naar `[x]` te
  wijzigen.
- Leg onder het stappenplan kort vast wat je hebt gedaan en waarom.
- Lees `development.md` en `technical-spec.md` voordat je code wijzigt.
- Backend Maven-root: `newsfeedbackend/newsfeedbackend/` — voer Maven-commando's
  uit vanuit die map.
- Nieuwe Flyway-migraties: `V<n+1>__<omschrijving>.sql` in
  `newsfeedbackend/newsfeedbackend/src/main/resources/db/migration/`.
- Laat geen raw JSON-artefacten (`agent_tips_update`, `phase`-objecten) achter
  in `docs/stories/*.md`; verwijder die altijd vóór commit.
- Gebruik `tail -20` + `wc -l` na elke Write-tool-aanroep op story-bestanden
  om te controleren dat er geen procesnotities zijn bijgeslopen.
