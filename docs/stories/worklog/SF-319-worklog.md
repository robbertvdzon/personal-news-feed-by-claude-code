# SF-319 - Worklog

Story-context bij eerste pickup:
Documentatie in lijn brengen met de code (subtaak SF-320, development). Doel:
documentatie corrigeren zodat die de huidige code weergeeft. **Geen** code- of
config-wijzigingen — alleen documentatiebestanden in scope.

## Ground truth (geverifieerd tegen de code)

- **Persistence:** PostgreSQL (Neon) + Flyway-migraties (V1..V15 in
  `src/main/resources/db/migration/`). Geen JSON-bestanden-op-schijf meer als
  primaire opslag; `data/` houdt alleen runtime-state + `external_calls.jsonl`
  audit-log. Podcast-audio als BYTEA in Postgres.
- **AI-provider:** uitsluitend **OpenAI** (SF-115/SF-116 migratie weg van
  Anthropic). Modellen per actie via `PNF_AI_MODEL_*` (defaults `gpt-5.4-mini` /
  `gpt-5.4` / `gpt-5.4-nano` / `gpt-4o-mini-transcribe`). Key `PNF_OPENAI_API_KEY`.
  Tavily (search), ElevenLabs/OpenAI-TTS (audio), Whisper (transcriptie).
- **Frontend:** `frontend/` = volledige app (5 tabs: Feed, RSS, Podcast, Events,
  Settings); `frontend-reader/` = read-only reader. Events: lijst + detail +
  **verwijderen** + discovery-trigger (Settings). **Geen** denylist-/
  event-preferences-beheer-UI in de frontend (alleen backend-endpoints).
- **OpenAPI ontbrekende endpoints t.o.v. code:** `/api/account/*`,
  `/api/shared/*`, `/api/admin/users*`, `/api/admin/costs/*` — toegevoegd.

## Stappenplan

- [x] read issue and target docs / ground truth verzamelen
- [x] `README.md` (root): AI → OpenAI, stack/persistence, frontends
- [x] `specs/README.md`: persistence, spec-bestandsnamen, architectuur
- [x] `specs/backend-functional-spec.md`: persistence, AI-provider, config
- [x] `specs/backend-technical-spec.md`: ai/storage-module, adapters, tests
- [x] `specs/frontend-spec.md`: AI-naamgeving, events/denylist-claims
- [x] `specs/openapi.yaml`: AI-namen + ontbrekende endpoints
- [x] `docs/factory/technical-spec.md` + `functional-spec.md`: AI → OpenAI, denylist-UI-claim
- [x] update story-log with results / interne consistentie + verificatie

## Done / rationale

Documentatie liep achter op twee grote migraties (Anthropic→OpenAI, JSON→Postgres)
en miste enkele endpoints + bevatte onjuiste frontend-claims (denylist-/
preferences-beheer-UI). Code is leidend; alle wijzigingen blijven binnen de
doc-scope. Geen geautomatiseerde tests van toepassing (alleen documentatie);
verificatie via grep dat de diff uitsluitend doc-bestanden raakt en
`docs/stories/**` (op deze worklog na) ongewijzigd is.
