# SF-670 - nightly: ADR-naleving herstellen

## Story

nightly: ADR-naleving herstellen

<!-- refined-by-factory -->

## Scope
Nightly controle of de code de vastgelegde architectuurbesluiten (ADR's) nog volgt, met herstel van afwijkingen die **gedrag-neutraal** kunnen.

Deze repo heeft géén `docs/adr/`-map of losse ADR-bestanden. De vastgelegde architectuurbesluiten leven als conventies in:
- `specs/backend-technical-spec.md` — Spring Modulith-modulegrenzen, lagen API → Domain → Infrastructure, DTO-regels, Jackson-gebruik, handmatig onderhouden OpenAPI-contract.
- `docs/factory/technical-spec.md` — codeconventies (geen comments tenzij WHY, `@param:`-target bij `@Value`, etc.).

Behandel deze twee documenten als de gezaghebbende ADR-set. Vergelijk de backend-Kotlincode (`newsfeedbackend/newsfeedbackend/`) en frontends (`frontend/`, `frontend-reader/`) tegen deze conventies en breng waar veilig mogelijk weer in lijn.

Buiten scope: integratietests/e2e-suite aanpassen, nieuwe functionaliteit, en het zelf herstellen van afwijkingen die een gedragswijziging vereisen.

## Acceptance criteria
- De code is vergeleken met de ADR-equivalenten (`specs/backend-technical-spec.md` + `docs/factory/technical-spec.md`); afwijkingen zijn benoemd.
- Veilig herstelbare (gedrag-neutrale) afwijkingen zijn hersteld. Een lege of kleine diff (vaak alleen het worklog) is een geldige uitkomst.
- Het **functionele gedrag is exact ongewijzigd**.
- Alle bestaande tests blijven slagen: `mvn test` draait groen (vereist `PNF_DATABASE_URL`).
- Integratietests en de e2e-suite (`e2e/scenarios/`) zijn niet gewijzigd.
- Afwijkingen waarvan herstel een functionele/gedragswijziging zou vergen, zijn **niet** zelf doorgevoerd maar als error/melding gedocumenteerd, met vermelding van welke ADR-conventie het betreft en wat er afwijkt.

## Aannames
- "ADR's" = de conventies in `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md` (er is geen `docs/adr/`-map).
- Bekende, structurele afwijkingen die níét veilig herstelbaar zijn — en dus gemeld in plaats van hersteld worden — zijn ten minste: Jackson-groupId `com.fasterxml.jackson` waar de spec `tools.jackson` noemt; cross-module imports tussen `domain`/`infrastructure`; en domeinmodellen die direct als HTTP-response worden teruggegeven.
- Er bestaat geen Spring Modulith `ApplicationModules.verify()`-test, dus modulegrens-drift wordt niet door de testsuite afgevangen; controle daarop is handmatig.
- `docs/factory/` is compleet (SF-220), dus er hoeft géén docs-aanvulling-acceptatiecriterium te worden toegevoegd.
- Het is een silent nightly-run: bij echte onduidelijkheid gaat de developer in error in plaats van te wachten.

## Eindsamenvatting

Ik heb alle context (`.task.md`, worklog, reviewer- en tester-comments). Hieronder de eindsamenvatting.

## Eindsamenvatting SF-670 — Nightly: ADR-naleving herstellen

**Uitkomst: worklog-only (geen code-wijziging) — geldige uitkomst.**

### Wat is gebouwd / gedaan
Dit is een nightly-controle of de code de vastgelegde architectuurbesluiten nog volgt. Omdat deze repo geen `docs/adr/`-map heeft, zijn `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md` als gezaghebbende ADR-set behandeld. De backend-Kotlincode en beide frontends zijn daartegen vergeleken. Er is **geen gedrag-neutrale afwijking** gevonden die hersteld moest worden → de codebase is sinds SF-586 al geconvergeerd. De enige diff is dit worklog (90 regels).

### Geverifieerde naleving (backend)
- `@Value`: geen kale `@Value` op primary-constructor-properties; de 2 resterende zijn de gedocumenteerde uitzonderingen (`PodcastAsyncConfig.kt` @Bean-param, `PodcastTranscriptWorker.kt` plain constructor-param) die terecht géén `@param:` krijgen.
- Logger-vorm (39/39), `external_call`-fallback via `log.warn`, DTO-plaatsing in `module/api/dto/`, KDoc-volgorde, geen OpenAPI-generator, en Jackson-groupId (`com.fasterxml.jackson`) zijn allemaal conform.
- Frontends ongewijzigd sinds SF-586; geen norm-afwijking binnen een app.

### Bewust niet gedaan (gemeld i.p.v. hersteld)
Vier vooraf-erkende, niet-veilig-herstelbare afwijkingen zijn gedocumenteerd, niet doorgevoerd (vergen een refactor/gedragswijziging, dus buiten scope):
1. Jackson-migratie naar Jackson 3 (`tools.jackson`) — risicovolle dependency-change, niet mechanisch.
2. Cross-module imports tussen `domain`/`infrastructure` van meerdere modules — architecturale refactor.
3. Domeinmodellen die direct als HTTP-response worden teruggegeven — vergt nieuwe DTO's + mapping.
4. `SettingsController.kt` zonder class-niveau `@RequestMapping` (bedient meerdere prefixes) — base-path zou URLs wijzigen.

### Keuzes
Conform de silent-nightly-aanpak is niet in error gegaan: de afwijkingen stonden expliciet als bekend/structureel in de aannames. Een lege/kleine diff is hier de juiste uitkomst.

### Tests
`mvn test` (newsfeedbackend): **BUILD SUCCESS, 28 tests groen** (0 failures, 0 errors) — door zowel developer als tester gedraaid en bevestigd. E2e-suite en integratietests zijn niet aangeraakt. Preview-browsertest overgeslagen omdat er 0 code-diff t.o.v. `main` is.

### Review & test-akkoord
Reviewer en tester zijn beiden akkoord: scope gerespecteerd, gedrag exact ongewijzigd, afwijkingen correct gemeld, geen rauwe JSON-artefacten in het worklog.
