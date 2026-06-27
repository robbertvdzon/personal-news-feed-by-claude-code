# SF-327 - Worklog

Story-context bij eerste pickup:
Documentatie verifiëren en corrigeren t.o.v. de code (geen code-wijzigingen)

Verifieer per documentatiegebied of de documentatie klopt met de huidige code en corrigeer aantoonbare drift, zonder enige productie-, test- of configuratiecode te wijzigen. In scope (wijzigbaar): README.md, runbook.md, specs/* (README, backend-functional-spec, backend-technical-spec, frontend-spec, e2e, openapi.yaml, branch-commit-convention), docs/factory/* incl. agents/*, en de run-worklog docs/stories/worklog/SF-327-worklog.md. Buiten scope (niet aanraken): newsfeedbackend/**, frontend/**, frontend-reader/**, e2e/**, deploy/**, .github/** en docs/stories/** (alleen leesbron). Code is leidend bij conflict.

Stappenplan:
[x]: read issue en target docs
[x]: ground-truth verzamelen uit code (backend endpoints, AI-config, persistence, metrics, Flutter-screens)
[x]: documentatie vergelijken met code en aantoonbare drift corrigeren
[x]: eigen review-stap (geen code-/configwijzigingen, docs intern consistent)
[x]: worklog bijwerken met resultaat

## Bevindingen (code = waarheid)

Geverifieerd tegen de code; veel was al correct na SF-319 (OpenAI, PostgreSQL/Neon+Flyway,
poorten, openapi-endpoints, model-defaults). Gecorrigeerde aantoonbare drift:

1. **Geen OpenAPI Generator in de build.** `pom.xml` bevat géén
   `openapi-generator-maven-plugin`; controllers zijn met de hand geschreven
   Spring `@RestController`-klassen (`@RequestMapping`/`@GetMapping`/…) en
   implementeren geen gegenereerde `*Api`-interfaces. `target/generated-sources/openapi/`
   bestaat niet. Gecorrigeerd in:
   - `specs/backend-technical-spec.md` §2 (Gegenereerde bronnen), §3 (rss-voorbeeld),
     §4 (Laag 1) en §8 (voorheen "Spec-first API (OpenAPI Generator)").
   - `docs/factory/technical-spec.md` (API-contract).
   - `docs/factory/development.md` (OpenAPI-first conventie).
   `openapi.yaml` blijft het handmatig onderhouden API-contract / source of truth.

2. **Spring Modulith-moduletabel onvolledig.** Code heeft top-level modules
   `admin`, `external_call`, `podcast_source`, `version` en `common` die in
   `specs/backend-technical-spec.md` §3 ontbraken — toegevoegd.

3. **Micrometer-metrics-tabel klopte niet.** De vier `newsfeed.ai.*`-metrics
   (`ai.calls.total/duration`, `ai.cost.usd`, `ai.retries`) bestaan niet in de
   code; wél bestaan o.a. `newsfeed.podcast.translated`, `newsfeed.requests.processed`,
   `newsfeed.events.*` en `newsfeed.event_videos.*`. Tabel rechtgetrokken naar de
   daadwerkelijk geregistreerde meters.

4. **Logformaat-claim onjuist.** Geen `application-dev.properties` en geen
   `logstash-logback-encoder` in de build → §5 Logformaat gecorrigeerd naar
   standaard Spring Boot Logback.

5. **`runbook.md` §6:** Flyway-migraties lopen t/m **V15** (`V15__image_url.sql`),
   niet t/m V14 — bijgewerkt.

6. **`docs/factory/technical-spec.md`:** publieke endpoints aangevuld met
   `/api/shared/**` en `/actuator/**` (stonden alleen in SecurityConfig).

7. **`specs/frontend-spec.md`:** stray "voor Claude" → "voor de AI-samenvatting"
   (de app draait volledig op OpenAI sinds SF-115/116).

## Niet gewijzigd (geverifieerd correct)

- `specs/openapi.yaml`: alle controller-paden/-methoden aanwezig en consistent.
- AI-provider/model-defaults, `PNF_AI_MODEL_*`, API-key-namen, persistence
  (PostgreSQL/Neon + Flyway), poorten (8080/3000/3100) — allemaal correct.
- Frontend-claims gecheckt tegen daadwerkelijke Flutter-screens.

## Tests/build

Dit is een pure documentatie-taak (geen productie-/test-/configcode gewijzigd),
dus er zijn geen nieuwe (unit)tests nodig of mogelijk; backend-build/CI is niet
beïnvloed. Verificatie is gedaan via grep/inspectie van de code (pom.xml,
controllers, application.properties, db/migration, MeterRegistry-aanroepen,
Flutter-screens).
