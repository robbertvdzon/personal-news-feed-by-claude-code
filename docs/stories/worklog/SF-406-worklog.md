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

## Review (SF-407, reviewer)

[info] Volledige story-diff t.o.v. `main` beoordeeld. Diff raakt uitsluitend
`specs/*`, `docs/factory/*` en deze worklog — geen broncode/build/migratie/
`docs/stories/`-wijzigingen. **AC1 voldaan.**

Alle doorgevoerde correcties tegen de code geverifieerd (code = leidend):
- Jackson `com.fasterxml.jackson` — bevestigd in `pom.xml` + alle Kotlin-imports; geen `tools.jackson`.
- Geen `ModuleStructureTest`/`ApplicationModules…verify()` in de repo (alleen 3 unit-tests) — correctie klopt.
- Frontend: geen "Open feed-item"-knop; "Wachtwoord wijzigen" → `PUT /api/account/password`; Debug/API-log-sectie (`ApiLogScreen`); "Over deze app" bovenaan — alle vier geverifieerd in `settings_screen.dart`/`api_log_screen.dart`.
- Base-URL default `http://localhost:8080`, prod `https://news.vdzonsoftware.nl` — bevestigd in `api_client.dart` + Makefiles (beide frontends).
- `AiPricingProperties.tokenCost`/`characterCost` i.p.v. `Pricing.openai*` — bevestigd; podcast-translate/tts gebruiken deze.
- `topic_history`/`rss_items` als Postgres-tabellen (geen JSON-op-schijf) — bevestigd.
- Migraties t/m `V15` (V4 ontbreekt, normaal) — klopt met runbook.
- `openapi.yaml` ongewijzigd en consistent met de controllers — bevestigd.

Geen blockers, bugs of regressies. AC1–AC6 voldaan. **Akkoord.**

## Test (SF-408, tester)

Verificatie van de volledige story-diff t.o.v. `main` via code-inspectie (docs-only
story; geen testcode geschreven of build gedraaid — dat zou AC1 schenden).

**AC1 — diff uitsluitend documentatie:** `git diff --name-only main...HEAD` filteren op
niet-`.md`/niet-worklog levert 0 bestanden → **alleen `.md`-bestanden gewijzigd**
(`specs/*`, `docs/factory/*` + deze worklog). Geen broncode/build/migratie/
`docs/stories/`-wijziging. ✓

**Doorgevoerde correcties tegen de code (code = leidend), alle bevestigd:**
- Jackson: `pom.xml` r.100-105 = `com.fasterxml.jackson.module/datatype`; `grep tools.jackson` → 0 treffers; alle imports `com.fasterxml.jackson`. ✓
- Geen `ApplicationModules`/`ModuleStructureTest` in de repo (`grep -rln` → leeg). ✓
- Base-URL: `api_client.dart` (beide frontends) `defaultValue: 'http://localhost:8080'`; prod via `Makefile PROD_API ?= https://news.vdzonsoftware.nl`. ✓
- Frontend `settings_screen.dart`: "Wachtwoord wijzigen" → `put('/api/account/password', …)` (r.317); "API-log" → `ApiLogScreen` (r.120-124); "Over deze app" (r.32) **boven** "Debug" (r.117). ✓
- `rss_detail_screen.dart`: alleen "Open bron"/"Origineel afspelen" + "Meer hierover"; **geen** "Open feed-item"-knop. ✓
- Pricing: `AiPricingProperties.tokenCost/characterCost` in OpenAiChatHttpClient/TtsClient/WhisperClient; `grep openaiGpt4oMiniCost|openaiTtsCost` → 0 treffers. ✓
- Persistentie: `grep topic_history.json|rss_items.json` → 0; `topic_history`/`rss_items` als tabellen in Flyway-migraties. ✓
- Migraties V1..V15 (V4 ontbreekt — normaal). ✓
- `openapi.yaml` ongewijzigd en dekt alle controller-mappings incl. `/api/account/{password,me}`, `/api/shared/{feed,categories}`, `/api/admin/users(+/password,/role,DELETE)`, `/api/admin/costs/{totals,daily,by-user,calls}`, `/api/events/{id}`. ✓
- `event-preferences`/`event-denylist` niet via frontend-UI (`grep frontend/ frontend-reader/` → 0) — consistent met `functional-spec.md`. ✓

Preview-omgeving (`pnf-pr-146`) levert de Flutter-SPA achter auth en bewijst hooguit
app-boot, niet doc-correctheid; code-inspectie is hier de juiste testmethode.

Geen drift gevonden buiten de reeds gecorrigeerde punten. AC1–AC6 voldaan.
**Test geslaagd.**

## Documentatie (SF-410, documenter)

Onafhankelijke documentatie-verificatieronde over de volledige set
(`README.md`, `runbook.md`, `specs/*`, `docs/factory/*`). De doorgevoerde
correcties zijn opnieuw tegen de code (leidende bron) gecontroleerd:

- Jackson: `pom.xml` r.100-105 = `com.fasterxml.jackson.module/datatype`;
  `grep -rln tools.jackson` over `.kt`/`.xml` → 0 treffers. ✓
- Geen `ApplicationModules`/`ModuleStructureTest` in de repo. ✓
- Base-URL: `api_client.dart` (beide frontends) `defaultValue: 'http://localhost:8080'`;
  `frontend/Makefile` + `frontend-reader/Makefile` `PROD_API ?= https://news.vdzonsoftware.nl`. ✓
- `settings_screen.dart`: "Over deze app" (r.32, bovenaan) vóór "Debug"/"API-log"
  (r.117/120, → `ApiLogScreen`); "Wachtwoord wijzigen" → `PUT /api/account/password` (r.317). ✓
- `rss_detail_screen.dart`: alleen "Open bron"/"Origineel afspelen" + "Meer hierover";
  geen "Open feed-item"-knop. ✓
- Pricing: `AiPricingProperties` aanwezig en gebruikt in chat/tts/whisper-clients;
  `grep openaiGpt4oMiniCost|openaiTtsCost` → 0 treffers. ✓
- AI-model-defaults in docs komen overeen met `application.properties`
  (`gpt-5.4-mini`/`gpt-5.4`/`gpt-5.4-nano`/`gpt-4o-mini-transcribe`). ✓

Volledige stale-ref-sweep over `README.md`, `runbook.md`, `specs/`, `docs/factory/`
(`tools.jackson`, `pnf.vdzon.com`, `topic_history.json`, `rss_items.json`,
`openaiGpt4oMiniCost`, `openaiTtsCost`, "JSON-bestanden") → **0 resterende treffers**.

Geen verdere documentatie-drift gevonden; alle relevante docs zijn in lijn met de code.
De documentatie-diff van deze story bevat uitsluitend documentatiebestanden + dit worklog.
