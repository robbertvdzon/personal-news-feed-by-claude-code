# SF-406 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope
Breng de volledige projectdocumentatie in lijn met de huidige code, zónder de broncode te wijzigen. Het gaat om een verificatie- en correctieronde over de documentatieset:
- `README.md` en `runbook.md` (repo-root)
- `specs/` — `README.md`, `backend-functional-spec.md`, `backend-technical-spec.md`, `frontend-spec.md`, `e2e.md`, `openapi.yaml`, `branch-commit-convention.md`
- `docs/factory/` — `README.md`, `deployment.md`, `development.md`, `functional-spec.md`, `technical-spec.md`, `secrets-local.md` en `agents/*.md`

De code (backend Kotlin/Spring, beide Flutter-frontends, migraties, config) is leidend; bij verschil wordt de documentatie aangepast, nooit de code. `docs/stories/` mag als input gebruikt worden (waaróm functionaliteit bestaat) maar wordt niet gewijzigd.

Aandachtspunten op basis van eerdere alignment-passes (SF-319/SF-327) — controleer of ze nog kloppen en blijven kloppen:
- Persistentie: PostgreSQL (Neon) + Flyway-migraties, niet "JSON-bestanden op schijf".
- AI-provider/model-config en bijbehorende defaults.
- API-contract in `specs/openapi.yaml` dekt de daadwerkelijke controller-endpoints (o.a. `/api/account`, `/api/shared`, `/api/admin/users`, `/api/admin/costs`, `/api/events/{id}`).
- Spec-bestandsnamen/repostructuur in `specs/README.md` verwijzen naar bestaande bestanden.
- Frontend-claims: beschreven UI-functionaliteit is ook echt aanwezig in de Flutter-screens (backend-endpoint ≠ frontend-implementatie — grep-verifiëren).

Buiten scope: elke wijziging aan broncode, build-config, migraties of `docs/stories/`. Een kleine of lege documentatie-diff is een geldige uitkomst als alles al klopt.

## Acceptance criteria
1. Er zijn **geen** wijzigingen aan broncode (backend Kotlin, Flutter-frontends, migraties, build-/deploy-config); de diff bevat uitsluitend documentatiebestanden (en de worklog). Wijzigt de code wél, dan wordt de story afgekeurd.
2. Elke geconstateerde discrepantie tussen documentatie en code is opgelost in de documentatie, met de code als leidende bron.
3. Functionaliteit die in de code aanwezig is, is terug te vinden in de documentatie; documentatie die functionaliteit beschrijft die niet (meer) in de code bestaat, is verwijderd of gecorrigeerd.
4. Beschreven frontend-functionaliteit is geverifieerd tegen de Flutter-screens (niet enkel tegen backend-endpoints).
5. `specs/openapi.yaml` is consistent met de daadwerkelijke endpoints en blijft de gezaghebbende API-contractbron.
6. De worklog (`docs/stories/worklog/SF-406-worklog.md`) documenteert welke documentatiebestanden zijn gecontroleerd en welke aanpassingen zijn gedaan (of dat er geen drift was).

## Aannames
- "Gehele documentatie" = de hierboven genoemde set (`README.md`, `runbook.md`, `specs/*`, `docs/factory/*`); `docs/stories/` is read-only context.
- De code wordt als correct beschouwd; bij twijfel wordt documentatie aan de code aangepast, niet andersom.
- Een lege of minimale doc-diff is acceptabel en betekent niet dat de story onvolledig is, mits de verificatie aantoonbaar is uitgevoerd (zichtbaar in de worklog).
- `docs/factory/` bestaat al en hoeft niet vanaf nul te worden opgezet; alleen inhoudelijke correcties waar nodig.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de diff gelezen. Dit is een docs-only story (SF-406, subtaak SF-409). Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-406: Documentatie in lijn brengen met de code

### Wat is gebouwd / gewijzigd
Een verificatie- en correctieronde over de volledige documentatieset, met de **code als leidende bron**. Uitsluitend documentatie is aangepast — geen broncode, build-config, migraties of `docs/stories/`. De diff (7 bestanden, +124/-15) raakt alleen `specs/*`, `docs/factory/*` en de worklog.

**Doorgevoerde correcties:**
- **`specs/backend-technical-spec.md`** — Jackson-groupId `tools.jackson` → `com.fasterxml.jackson` (conform pom.xml + imports); Modulith verify-test verduidelijkt als nog-niet-aanwezig in de repo.
- **`specs/backend-functional-spec.md`** — JSON-op-schijf-restanten verwijderd: `topic_history.json`/`rss_items.json` → Postgres-tabellen; verouderde kostenbron `Pricing.openai*` → `AiPricingProperties.tokenCost/characterCost`.
- **`specs/frontend-spec.md`** — niet-bestaande knop "Open feed-item" verwijderd; "Over deze app" gecorrigeerd naar bovenaan settings; ontbrekende features toegevoegd ("Wachtwoord wijzigen" → `PUT /api/account/password`, Debug/API-log-sectie); base-URL default gecorrigeerd naar `http://localhost:8080` (prod: `https://news.vdzonsoftware.nl`).
- **`docs/factory/technical-spec.md`** — Jackson-correctie + verify-test als optioneel/nog-niet-aanwezig.
- **`docs/factory/development.md`** — `ModuleStructureTest`-conventie verduidelijkt (bestaat nog niet in de repo).
- **`docs/factory/secrets-local.md`** — `API_BASE_URL`-default gecorrigeerd naar `http://localhost:8080`.

**Gecontroleerd, geen drift:** `README.md`, `runbook.md`, `specs/README.md`, `specs/e2e.md`, `specs/branch-commit-convention.md`, `docs/factory/README.md`, `deployment.md`, `agents/*.md` en — belangrijk — **`specs/openapi.yaml`**, dat volledig consistent is met alle controllers (incl. `/api/account`, `/api/shared`, `/api/admin/users`, `/api/admin/costs`, `/api/events/{id}`) en daarom ongewijzigd is gebleven.

### Belangrijkste keuzes
- Bij elk verschil is de **documentatie** aangepast, nooit de code (acceptatiecriterium AC1/AC2).
- Frontend-claims zijn niet alleen tegen backend-endpoints maar **tegen de Flutter-screens** geverifieerd via grep (`settings_screen.dart`, `rss_detail_screen.dart`, `api_log_screen.dart`, beide frontends).
- `openapi.yaml` blijft de gezaghebbende API-contractbron en bleek al accuraat.

### Wat is getest
Docs-only story → geen unit-test geschreven of build gedraaid (dat zou broncode toevoegen en AC1 schenden). Verificatie is gedaan via **code-inspectie en grep** tegen de leidende bron (controllers, `application.properties`, Flyway-migraties V1..V15, Flutter-screens). Reviewer (SF-407) en tester (SF-408) hebben de volledige diff onafhankelijk tegen de code geverifieerd: alle correcties bevestigd, diff bevat uitsluitend documentatie, geen blockers/regressies. **AC1–AC6 voldaan; review en test akkoord.**

### Bewust niet gedaan
- Geen broncode-, build-, migratie- of `docs/stories/`-wijzigingen (buiten scope; zou de story afkeuren).
- Geen browser-/preview-test: de preview levert enkel de Flutter-SPA achter auth en bewijst hooguit app-boot, niet doc-correctheid.
- `openapi.yaml` bewust ongewijzigd gelaten — was al consistent.

---
