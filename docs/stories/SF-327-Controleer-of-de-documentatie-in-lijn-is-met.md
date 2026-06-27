# SF-327 - Controleer of de documentatie in lijn is met de code (geen code-wijzigingen)

## Story

Controleer of de documentatie in lijn is met de code (geen code-wijzigingen)

<!-- refined-by-factory -->

## Scope

Controleer of de volledige projectdocumentatie nog in lijn is met de **huidige code** en corrigeer aantoonbare afwijkingen, **zonder ook maar één regel productie-, test- of configuratiecode te wijzigen**. Code is leidend; documentatie volgt. Alle functionaliteit die in de code zit moet correct in de documentatie staan; documentatie die niet (meer) met de code klopt wordt gecorrigeerd of verwijderd.

**Documentbestanden die aangepast mogen worden (in scope):**
- `README.md` (root)
- `runbook.md`
- `specs/` — `README.md`, `backend-functional-spec.md`, `backend-technical-spec.md`, `frontend-spec.md`, `e2e.md`, `openapi.yaml`, `branch-commit-convention.md`
- `docs/factory/` — `README.md`, `functional-spec.md`, `technical-spec.md`, `development.md`, `deployment.md`, `secrets-local.md`, `agents/*.md`

**Expliciet buiten scope (NIET wijzigen):**
- Alle code en config: `newsfeedbackend/**`, `frontend/**`, `frontend-reader/**`, `e2e/**` (testcode), `deploy/**`, `.github/**`, build-/configbestanden.
- `docs/stories/**` (incl. bestaande worklogs) — uitsluitend als lees-/inputbron gebruiken om te begrijpen waaróm functionaliteit bestaat, nooit aanpassen. Uitzondering: de run-worklog `docs/stories/worklog/SF-327-worklog.md` mag wel worden bijgewerkt.
- Secrets/secret-waarden — niet aan documentatie toevoegen.

**Aanpak (richtinggevend, geen harde eis):** vergelijk per documentatiegebied de beschrijving met de werkelijke code:
- backend-endpoints/controllers t.o.v. `specs/openapi.yaml` (gezaghebbende bron voor het API-contract);
- Flutter-screens t.o.v. `frontend-spec.md` — verifieer frontend-claims altijd via grep tegen de daadwerkelijke screens (backend-endpoint aanwezig ≠ frontend geïmplementeerd);
- backend-gedrag/pipelines/persistencelaag/AI-provider t.o.v. de backend-specs;
- build-/run-/deploy-commando's en poorten t.o.v. de echte scripts/config.

Aangezien SF-319 recent al een brede alignment-pass heeft uitgevoerd, mag de uitkomst een kleine of zelfs lege documentatie-diff zijn als de documentatie al klopt. Het doel is een correcte eindstaat, niet het forceren van wijzigingen.

## Acceptance criteria

1. **Geen code-/configwijzigingen.** De PR-diff (`main...HEAD`) bevat uitsluitend wijzigingen in documentatiebestanden binnen de bovenstaande scope. Er zijn geen wijzigingen in `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `e2e/`, `deploy/`, `.github/` of build-/configbestanden. (Reviewer controleert dit expliciet; de tester keurt af zodra er code-wijzigingen zijn.)
2. **`docs/stories/**` is onveranderd**, met uitzondering van de run-worklog `docs/stories/worklog/SF-327-worklog.md`. Bestaande story-/worklog-content is alleen als leesbron gebruikt.
3. **Documentatie klopt met de code:** beschrijvingen die aantoonbaar afwijken van de huidige code zijn gecorrigeerd; documentatie beschrijft geen functionaliteit die niet (meer) in de code bestaat. Aandachtspunten op basis van eerdere drift (alleen aanpassen als de code nú afwijkt van de doc):
   - Persistencelaag correct als **PostgreSQL (Neon) met Flyway-migraties** (geen "geen database / JSON-bestanden op schijf"-claims).
   - AI-provider/-modelconfiguratie consistent met de code (provider, `PNF_AI_MODEL_*`-defaults, API-key-namen).
   - Spec-bestandsnamen verwijzen naar werkelijk bestaande bestanden.
4. **Volledigheid:** functionaliteit die in de code aanwezig is maar in de documentatie ontbreekt, is toegevoegd; frontend-functionaliteit wordt alleen gedocumenteerd als er een bijbehorend Flutter-scherm bestaat.
5. **API-contract:** `specs/openapi.yaml` is consistent met de daadwerkelijk geëxposeerde backend-endpoints (paden, methoden, request/response). Afwijkingen worden in de openapi rechtgetrokken richting de code, niet andersom.
6. **Interne consistentie:** `README.md`, `runbook.md`, `specs/` en `docs/factory/` spreken elkaar niet tegen over stack, poorten, persistence, AI-provider en kernfunctionaliteit.
7. **Minimaliteit:** wijzigingen blijven beperkt tot wat nodig is om documentatie en code te laten matchen; geen ongerelateerde herstructurering. Als de documentatie al volledig in lijn is, is een lege of minimale doc-diff een geldig eindresultaat.

## Aannames

- **Code is leidend, documentatie volgt.** Bij conflict tussen code en documentatie (inclusief `specs/openapi.yaml`, normaal "source of truth") wordt de documentatie aangepast aan het werkelijke codegedrag — niet de code.
- "Documentatie" omvat de hierboven genoemde Markdown- en spec-bestanden (root, `specs/`, `docs/factory/`); `docs/stories/**` valt nadrukkelijk buiten de wijzigbare set, op de run-worklog na.
- Afgeronde stories in `docs/stories/` weerspiegelen geland gedrag en mogen als verklarende bron dienen; bij twijfel is de code de waarheidsmaatstaf.
- Het bijwerken van `docs/stories/worklog/SF-327-worklog.md` valt buiten het "niet aanpassen van docs/stories"-verbod, conform de factory-werkwijze.
- Er zijn geen nieuwe documentatiebestanden vereist; bestaande bestanden worden gecorrigeerd/aangevuld. Een nieuw doc-bestand toevoegen mag als de structuur dat logisch vereist, maar is geen doel op zich.

## Eindsamenvatting

Ik heb alle context: `.task.md`, het worklog en de werkelijke story-diff. De diff bevestigt dat alleen documentatiebestanden zijn gewijzigd. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting SF-327 — Documentatie in lijn brengen met de code

**Doel:** controleren of de volledige projectdocumentatie nog klopt met de huidige code en aantoonbare afwijkingen corrigeren — **zonder één regel productie-, test- of configuratiecode te wijzigen**. Code is leidend, documentatie volgt.

### Wat is gebouwd / gewijzigd
Een pure documentatie-correctie. De story-diff (`main...HEAD`) raakt uitsluitend doc-bestanden plus de run-worklog:
- `specs/backend-technical-spec.md` (grootste correctie)
- `docs/factory/technical-spec.md`, `docs/factory/development.md`
- `runbook.md`
- `specs/frontend-spec.md`
- `docs/stories/worklog/SF-327-worklog.md` (run-worklog, toegestaan)

**Geen wijzigingen** in `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `e2e/`, `deploy/`, `.github/` of build-/configbestanden.

### Belangrijkste gecorrigeerde drift (code = waarheid)
1. **OpenAPI Generator bestaat niet in de build.** `pom.xml` bevat geen `openapi-generator-maven-plugin`; controllers zijn handgeschreven Spring `@RestController`-klassen, geen gegenereerde `*Api`-interfaces. "Spec-first/OpenAPI-first generatie"-claims verwijderd; `openapi.yaml` blijft het handmatig onderhouden API-contract.
2. **Spring Modulith-moduletabel aangevuld** met de echt bestaande modules `admin`, `external_call`, `podcast_source`, `version`, `common`.
3. **Micrometer-metricstabel rechtgetrokken:** de gedocumenteerde `newsfeed.ai.*`-metrics bestaan niet in de code; vervangen door de werkelijk geregistreerde meters (`newsfeed.podcast.translated`, `newsfeed.requests.processed`, `newsfeed.events.*`, `newsfeed.event_videos.*`).
4. **Logformaat-claim gecorrigeerd:** geen `application-dev.properties` / `logstash-logback-encoder` aanwezig → standaard Spring Boot Logback.
5. **Flyway-migraties** lopen t/m **V15** (`V15__image_url.sql`), was gedocumenteerd als V14.
6. **Publieke endpoints** aangevuld met `/api/shared/**` en `/actuator/**` (conform `SecurityConfig`).
7. **Frontend-spec:** restant "voor Claude" → "voor de AI-samenvatting" (de app draait volledig op OpenAI sinds SF-115/116).

### Gemaakte keuzes
- Omdat SF-319 recent al een brede alignment-pass deed, was veel al correct (OpenAI-provider, PostgreSQL/Neon + Flyway, poorten 8080/3000/3100, model-defaults `PNF_AI_MODEL_*`, openapi-paden). Er is bewust **minimaal** gecorrigeerd — alleen aantoonbare afwijkingen, geen herstructurering.

### Wat is getest
- Doc-only PR: geverifieerd via code-inspectie (grep/lezen van `pom.xml`, controllers, `application.properties`, `db/migration`, MeterRegistry-aanroepen, Flutter-screens) en interne consistentiecheck van de markdown. Geen browser-/live-test nodig.
- Reviewer (SF-328) heeft de volledige diff tegen de code geverifieerd en akkoord gegeven; geen code-/configwijzigingen, docs intern consistent.

### Bewust niet gedaan
- Geen code, tests of config gewijzigd (buiten scope en expliciet verboden).
- `openapi.yaml` niet aangepast: alle controller-paden/-methoden bleken al consistent.
- Bestaande `docs/stories/**` ongemoeid gelaten (alleen als leesbron gebruikt), op de toegestane run-worklog na.
- Geen nieuwe documentatiebestanden toegevoegd — niet nodig.

Alle acceptatiecriteria zijn gehaald.
