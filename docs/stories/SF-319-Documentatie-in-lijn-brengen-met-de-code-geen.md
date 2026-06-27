# SF-319 - Documentatie in lijn brengen met de code (geen code-wijzigingen)

## Story

Documentatie in lijn brengen met de code (geen code-wijzigingen)

<!-- refined-by-factory -->

## Scope

Breng de **documentatie** van de repo in lijn met de **huidige code**, zonder ook maar één regel productie- of testcode te wijzigen. Documentatie is leidend door de code: alle functionaliteit die in de code zit moet correct in de documentatie staan, en documentatie die niet (meer) klopt met de code wordt gecorrigeerd of verwijderd.

**Documentbestanden die aangepast mogen worden (in scope):**
- `README.md` (root)
- `runbook.md`
- `specs/` — `README.md`, `backend-functional-spec.md`, `backend-technical-spec.md`, `frontend-spec.md`, `e2e.md`, `openapi.yaml`, `branch-commit-convention.md`
- `docs/factory/` — `README.md`, `functional-spec.md`, `technical-spec.md`, `development.md`, `deployment.md`, `secrets-local.md`, `agents/*.md`

**Expliciet buiten scope (NIET wijzigen):**
- Alle code: `newsfeedbackend/**`, `frontend/**`, `frontend-reader/**`, `e2e/**` (testcode), `deploy/**`, `.github/**`, build-/configbestanden.
- `docs/stories/**` (incl. worklogs) — alleen als **input/leesbron** gebruiken om te begrijpen waaróm functionaliteit bestaat, nooit aanpassen.
- Secrets/secret-waarden — niet toevoegen aan documentatie.

**Aanpak (richtinggevend, geen harde eis):** vergelijk per documentatiegebied de beschrijving met de werkelijke code (controllers/endpoints t.o.v. `specs/openapi.yaml`, Flutter-screens t.o.v. `frontend-spec.md`, backend-gedrag/pipelines/persistencelaag t.o.v. de backend-specs, build-/run-commando's t.o.v. de echte scripts). Backend-endpoint aanwezig ≠ frontend geïmplementeerd: verifieer frontend-claims tegen de daadwerkelijke Flutter-screens.

## Acceptance criteria

1. **Geen codewijzigingen.** De diff van de PR bevat uitsluitend wijzigingen in documentatiebestanden (zie scope). Er zijn geen wijzigingen in `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `e2e/`, `deploy/`, of andere code/config. (Reviewer controleert dit expliciet; tester keurt af zodra er codewijzigingen zijn.)
2. **`docs/stories/**` is onveranderd** — wel als bron gebruikt, niet gewijzigd.
3. **Documentatie klopt met de code:** beschrijvingen die aantoonbaar afwijken van de huidige code zijn gecorrigeerd. Concrete bekende drift die geadresseerd moet zijn:
   - De persistencelaag wordt correct beschreven als **PostgreSQL (Neon) met Flyway-migraties**; verouderde claims als "geen database / JSON-bestanden op schijf" (o.a. `specs/README.md`) zijn verwijderd/gecorrigeerd.
   - Verwijzingen naar spec-bestandsnamen kloppen met de werkelijke bestanden (bv. `backend-spec.md` → `backend-functional-spec.md`/`backend-technical-spec.md`).
4. **Volledigheid:** functionaliteit die in de code aanwezig is maar in de documentatie ontbreekt, is toegevoegd (bv. de events-ontdekking + denylist en het verwijderen van events, voor zover die in de code zitten). De documentatie beschrijft geen functionaliteit die niet (meer) in de code bestaat.
5. **API-contract:** `specs/openapi.yaml` is consistent met de daadwerkelijk door de backend geëxposeerde endpoints (paden, methoden, request/response). Afwijkingen worden in de documentatie (openapi) rechtgetrokken richting de code, niet andersom.
6. **Interne consistentie:** README, runbook, `specs/` en `docs/factory/` spreken elkaar niet tegen over stack, poorten, persistence en kernfunctionaliteit.
7. De wijzigingen zijn beperkt tot wat nodig is om documentatie en code te laten matchen; geen ongerelateerde herstructurering.

## Aannames

- **Code is leidend, documentatie volgt.** Waar code en documentatie (inclusief `specs/openapi.yaml`, dat normaal "source of truth" heet) van elkaar afwijken, wordt de documentatie aangepast aan het werkelijke codegedrag — niet de code.
- "Documentatie" omvat de hierboven genoemde Markdown- en spec-bestanden (root, `specs/`, `docs/factory/`); `docs/stories/**` valt er nadrukkelijk buiten als wijzigbaar bestand.
- De afgeronde stories in `docs/stories/` weerspiegelen functionaliteit die in de code is geland; ze mogen als verklarende bron worden gebruikt, maar de code zelf is de waarheidsmaatstaf bij twijfel.
- Het bijwerken van de `docs/stories/worklog/SF-319-worklog.md` voor deze story-uitvoering valt buiten het "niet aanpassen van docs/stories"-verbod (dat verbod betreft bestaande story-/worklog-content als input), conform de factory-werkwijze.
- Frontend-claims worden geverifieerd tegen de Flutter-screens; een bestaand backend-endpoint zonder bijbehorende frontend-implementatie wordt niet als frontend-functionaliteit gedocumenteerd.
- Er zijn geen nieuwe documentatiebestanden vereist; bestaande bestanden worden gecorrigeerd/aangevuld. Het toevoegen van een nieuw doc-bestand is toegestaan als bestaande structuur dat logisch vereist, maar is geen doel op zich.

## Eindsamenvatting

Ik heb `.task.md`, het worklog (`SF-319-worklog.md`) en de review-notities gelezen en de story-diff gecontroleerd. Hieronder de eindsamenvatting.

---

## Eindsamenvatting — SF-319: Documentatie in lijn brengen met de code

**Doel:** de repo-documentatie laten kloppen met de huidige code, zónder ook maar één regel productie- of testcode te wijzigen. Code is leidend; documentatie volgt.

### Wat is gebouwd / gewijzigd
Uitsluitend documentatiebestanden zijn aangepast. De diff (`main...HEAD`) raakt:
- `README.md`, `docs/factory/` (development, functional-spec, technical-spec)
- `specs/` — `README.md`, `backend-functional-spec.md`, `backend-technical-spec.md`, `frontend-spec.md`, `openapi.yaml`
- `docs/stories/worklog/SF-319-worklog.md` (toegestaan als run-worklog)

Geen wijzigingen in `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `e2e/`, `deploy/`, `.github/` of build-/configbestanden.

### Belangrijkste correcties (drift weggewerkt)
- **Persistence:** verouderde claims ("geen database / JSON-bestanden op schijf") vervangen door de werkelijkheid: **PostgreSQL (Neon) met Flyway-migraties** (V1..V15). `data/` houdt alleen runtime-state + `external_calls.jsonl` audit-log; podcast-audio staat als BYTEA in Postgres.
- **AI-provider:** documentatie liep achter op de migratie van Anthropic → **OpenAI**. Modellen per actie via `PNF_AI_MODEL_*` (defaults `gpt-5.4-mini` / `gpt-5.4` / `gpt-5.4-nano` / `gpt-4o-mini-transcribe`), key `PNF_OPENAI_API_KEY`. Conferentie-/tool-namen (Tavily, ElevenLabs, Whisper) terecht behouden.
- **Spec-bestandsnamen:** verwijzingen rechtgetrokken (o.a. `backend-spec.md` → `backend-functional-spec.md` / `backend-technical-spec.md`).
- **OpenAPI:** ontbrekende endpoints toegevoegd zodat het contract de controllers 1-op-1 dekt: `/api/account/*`, `/api/shared/*` (publiek, `security:[]`), `/api/admin/users*`, `/api/admin/costs/*` (ADMIN).
- **Frontend-claims:** Events-functionaliteit gedocumenteerd zoals daadwerkelijk geïmplementeerd (lijst, detail, verwijderen, discovery-trigger). Onjuiste claim van een **denylist-/event-preferences-beheer-UI** verwijderd — die endpoints bestaan alleen in de backend, niet als Flutter-scherm.

### Gemaakte keuzes
- Bij elke conflict tussen code en documentatie (inclusief `specs/openapi.yaml`, normaal "source of truth") is de **documentatie** aangepast aan het codegedrag.
- Backend-endpoint zonder bijbehorend Flutter-scherm wordt **niet** als frontend-feature gedocumenteerd.
- Geen nieuwe doc-bestanden; alleen bestaande gecorrigeerd/aangevuld. Geen ongerelateerde herstructurering.

### Wat is getest/geverifieerd
- **Review (SF-320):** akkoord, geen blockers. Scope, persistence (`spring.datasource` + Flyway V1..V15), AI-config (`application.properties`), openapi-endpoints (36 `$ref`s resolven, geen tabs), en frontend-claims tegen de Flutter-screens geverifieerd tegen de code.
- **Test (SF-321):** test-approved. Voor een docs-only PR bestaat het testwerk uit code-inspectie van de implementatieclaims + interne consistentiecheck van de markdown; geen browser-/live-preview-test nodig.
- Test-suite-claim in de docs klopt (3 unit-tests; geen Cucumber/WireMock/ModuleStructureTest). Geen stray factory-JSON in gecommitte doc-bestanden.

### Bewust niet gedaan
- Geen code-, test- of config-wijzigingen (per scope verboden).
- Bestaande `docs/stories/**`-content niet gewijzigd (alleen als leesbron gebruikt; de run-worklog wel bijgewerkt).
- Geen secrets/secret-waarden aan documentatie toegevoegd.

---
