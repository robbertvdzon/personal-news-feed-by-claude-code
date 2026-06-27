# SF-406 — Worklog

Story: Documentatie in lijn brengen met de code (subtaak SF-407, developer).

Doel: de volledige documentatieset verifiëren tegen de huidige code en
discrepanties uitsluitend in de docs corrigeren (code is leidend). Geen
broncode-, build-, migratie- of `docs/stories/`-wijzigingen.

## Stappenplan

- [x] Issue + factory-docs gelezen (.task.md, docs/factory, specs)
- [x] Backend in kaart gebracht: controllers/endpoints, AI-config + defaults, persistentie, modules
- [x] Frontend in kaart gebracht: schermen + features in `frontend/` en `frontend-reader/`
- [x] `openapi.yaml` endpoint-voor-endpoint getoetst tegen de controllers
- [x] Discrepanties uitsluitend in de docs gecorrigeerd
- [x] Eigen review-stap + controle dat de diff alleen docs raakt

## Verificatie tegen de code (leidende bron)

**Backend-endpoints** — alle controllers gemapt (`AuthController`, `AccountController`,
`AdminController`, `AdminCostsController`, `FeedController`, `SharedFeedController`,
`RssController`, `SettingsController`, `EventController`, `PodcastController`,
`PodcastTranslationController`, `RequestController`, `VersionController`).
`specs/openapi.yaml` dekt ze allemaal, inclusief `/api/account/{password,me}`,
`/api/shared/{feed,categories}`, `/api/admin/users(+/password,/role,DELETE)`,
`/api/admin/costs/{totals,daily,by-user,calls}` en `/api/events/{id}`. **Geen drift in openapi.yaml.**

**AI-config** — provider is OpenAI; defaults `gpt-5.4-mini` / `gpt-5.4` /
`gpt-5.4-nano` / `gpt-4o-mini-transcribe` (klopt met `application.properties`).
Persistentie = PostgreSQL (Neon) + Flyway (V1..V15). **Klopt met de docs.**

**Spec-bestandsnamen** in `specs/README.md` verwijzen allemaal naar bestaande bestanden. **Geen drift.**

## Gevonden drift en correcties (alleen docs)

| Bestand | Correctie |
|---|---|
| `specs/backend-technical-spec.md` | Jackson-groupId: `tools.jackson` → `com.fasterxml.jackson` (pom.xml + imports gebruiken fasterxml). Modulith verify-test: beschreven alsof aanwezig → verduidelijkt dat die test (nog) niet in de repo bestaat (consistent met §7). |
| `specs/backend-functional-spec.md` | JSON-op-schijf-restanten: `topic_history.json` (2×) → `topic_history`-tabel, `rss_items.json` → `rss_items`-tabel. Verouderde kostenbron `Pricing.openaiGpt4oMiniCost`/`openaiTtsCost` → `AiPricingProperties.tokenCost`/`characterCost` (Pricing.kt houdt alleen nog ElevenLabs/Tavily). |
| `specs/frontend-spec.md` | Niet-bestaande knop "Open feed-item" op RssItemDetailScreen verwijderd (scherm heeft alleen "Open bron"/"Origineel afspelen" + "Meer hierover"). "Over deze app" staat **bovenaan** (niet onderaan) het settings-scherm. Ontbrekende, wél-aanwezige features toegevoegd: "Wachtwoord wijzigen" (`PUT /api/account/password`) en de Debug/API-log-sectie. Base-URL default `https://pnf.vdzon.com` → werkelijke code-default `http://localhost:8080` (prod-builds: `https://news.vdzonsoftware.nl`). |
| `docs/factory/technical-spec.md` | Jackson-groupId-correctie; verify-test als optioneel/nog-niet-aanwezig; verwijzing dat de modultabel de belangrijkste modules toont (volledige lijst in backend-technical-spec §3). |
| `docs/factory/development.md` | `ModuleStructureTest`-conventie verduidelijkt: die test bestaat nog niet in de repo. |
| `docs/factory/secrets-local.md` | `API_BASE_URL`-default `https://pnf.vdzon.com` → `http://localhost:8080` (prod-builds: `https://news.vdzonsoftware.nl`). |

## Gecontroleerd, geen drift

- `README.md`, `runbook.md` — OpenAI, PostgreSQL/Neon/Flyway (t/m V15), tabellen, reader op `:3100`: klopt.
- `specs/README.md`, `specs/e2e.md`, `specs/branch-commit-convention.md` — accuraat.
- `specs/openapi.yaml` — volledig consistent met de controllers (ongewijzigd gelaten).
- `docs/factory/README.md`, `deployment.md`, `agents/*.md` — geen code-claim-drift; `event_preferences`/`event_denylist` worden inderdaad niet via een frontend-Settings-UI beheerd (grep `frontend/` + `frontend-reader/` → geen treffers), zoals `functional-spec.md` al stelt.

## Tests / build

Dit is een **docs-only** story. AC1 eist dat de diff uitsluitend documentatie raakt;
broncode toevoegen (incl. testcode) zou de story afkeuren. Er is daarom geen unit-test
geschreven of build/test gedraaid — verificatie is gedaan via code-inspectie en grep
tegen de leidende bron (controllers, `application.properties`, Flyway-migraties, Flutter-screens).
De diff bevat alleen `specs/*` en `docs/factory/*` (+ deze worklog).
