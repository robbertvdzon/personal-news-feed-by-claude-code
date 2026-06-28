# SF-608 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope
Gedrag-neutrale consistentie-pass over de codebase: zoek afwijkende patronen en breng ze in lijn met de heersende norm. Geen functionele wijzigingen.

In scope:
- **Backend** (`newsfeedbackend/newsfeedbackend/`, Kotlin/Spring Modulith) en de twee Flutter-frontends (`frontend/`, `frontend-reader/`).
- Gladstrijken van: afwijkende naamgeving, klasse-/bestandsstructuur, error-handling, logging-niveaus en API-conventies t.o.v. de rest van de codebase.
- Plekken waar hetzelfde probleem op meerdere manieren is opgelost gelijktrekken naar één patroon.

Norm (leidend, in deze volgorde):
- `docs/factory/technical-spec.md` §Codeconventies (o.a. geen comments tenzij WHY, `@param:`-target bij `@Value`).
- `specs/backend-technical-spec.md` (lagen API→domain→infrastructure, Spring Modulith-modulegrenzen, logging-niveaus, DTO-regels).
- `specs/openapi.yaml` als gezaghebbende bron voor het API-contract.

Buiten scope:
- Functionele wijzigingen van welke aard dan ook.
- Integratietests en de e2e-suite (`e2e/scenarios/`): niet aanpassen.
- Aanvullen van `docs/factory/` (al compleet sinds SF-220).
- Bekende structurele afwijkingen die niet veilig gedrag-neutraal te herstellen zijn (zie Aannames) — deze worden gemeld, niet gefixt.

## Acceptance criteria
- Afwijkende patronen in backend en/of frontends zijn in lijn gebracht met de genoemde norm-documenten, zónder verandering van functioneel gedrag.
- Waar hetzelfde probleem op meerdere manieren werd opgelost, is één consistent patroon gekozen.
- Bestaande tests slagen onveranderd: `mvn test` op de backend is groen (vereist `PNF_DATABASE_URL`); de Flutter-tests blijven groen.
- Integratietests en e2e-scenario's zijn niet gewijzigd. Was zo'n wijziging nodig om het werk groen te krijgen, dan is dat een gedragsverandering → de developer gaat in error i.p.v. de test aan te passen.
- Een lege of kleine diff is een geldige uitkomst wanneer er geen veilige, gedrag-neutrale verbetering te maken is.
- Niet veilig gladstrijkbare afwijkingen (gedragsrisico) zijn als melding/error gedocumenteerd in het worklog i.p.v. doorgevoerd.

## Aannames
- SF-608 is een herhaling van de nightly consistentie-job (familie SF-523/SF-343); identieke storybron staat in `.factory/nightly/consistency/story.md`.
- "De codebase" omvat de backend-Maven-module `newsfeedbackend/newsfeedbackend/` en de twee Flutter-frontends `frontend/` en `frontend-reader/`.
- `docs/factory/` is compleet (SF-220); er hoeft geen docs-aanvulling-acceptatiecriterium toegevoegd te worden.
- De volgende bekende afwijkingen zijn structureel/niet veilig gedrag-neutraal te herstellen en worden daarom gemeld, niet gewijzigd: Jackson-groupId `com.fasterxml.jackson` vs. de in de spec genoemde `tools.jackson`; cross-module imports tussen domain en infrastructure; domeinmodellen die direct als HTTP-response worden teruggegeven.
- Dit is een silent nightly-run: bij echte onduidelijkheid gaat de developer in error in plaats van te wachten op input.

## Eindsamenvatting

## Eindsamenvatting SF-608 — Consistentie: afwijkende patronen gladstrijken

**Resultaat: lege code-diff (geldige uitkomst per acceptatiecriterium).** De codebase bleek al in lijn met de veilig-herstelbare conventies; er is geen gedrag-neutrale verbetering doorgevoerd. De PR bevat alleen het story-log en worklog.

### Wat is gedaan
Een gedrag-neutrale consistentie-pass over backend (`newsfeedbackend/`) en de twee Flutter-frontends (`frontend/`, `frontend-reader/`), getoetst aan `technical-spec.md §Codeconventies`, `specs/backend-technical-spec.md` en `specs/openapi.yaml`. De pass is uitgevoerd als verificatie via grep; er is geen afwijking gevonden die veilig en mechanisch glad te strijken was.

### Geverifieerd conform
- **`@Value`**: 21× met `@param:`-target, 0 kale `@Value`; de 2 uitzonderingen (`PodcastAsyncConfig` @Bean-param, `PodcastTranscriptWorker` plain constructor-param) staan terecht zonder target.
- **Logging**: 39/39 loggers volgens `private val log = LoggerFactory.getLogger(javaClass)`; external_call-fallback 10/10 op WARN met consistente tag-prefix.
- **Jackson**: 36× `com.fasterxml.jackson`, 0× `tools.jackson` — consistent met pom.xml én de inmiddels aligned spec.
- **DTO/structuur**: geen inline `data class` in `*Controller.kt`; geen `openapi-generator`-plugin; KDoc-positie conform.
- **Frontends**: geen `print(...)`-debuglogging in beide `lib/`-dirs; cross-app-verschillen zijn geen norm binnen één app en zijn niet gelijkgetrokken.

### Keuzes
- Conform vorige nightly-runs (SF-392/456/502/586) is **empty-diff als eindresultaat** geaccepteerd i.p.v. geforceerde wijzigingen.
- Bekende structurele afwijkingen zijn **gemeld, niet gefixt** (vooraf erkend in de story-aannames), omdat herstel niet gedrag-neutraal te garanderen is:
  1. Jackson `com.fasterxml.jackson` i.p.v. `tools.jackson` (risicovolle dependency-/Jackson-3-migratie).
  2. Cross-module imports tussen `domain`/`infrastructure` (Spring Modulith-overtredingen; architecturale refactor).
  3. Domeinmodellen direct als HTTP-response i.p.v. response-DTO's.
  4. `SettingsController` zonder klasse-`@RequestMapping` (base-path zou URL's wijzigen = gedragsverandering).

### Tests
- Backend `mvn test`: **BUILD SUCCESS — 28 tests, 0 failures, 0 errors** (DB-vrije unit-tests; onafhankelijk gereproduceerd door tester).
- Flutter-tests niet lokaal gedraaid (geen flutter-binary op de runner); geen frontend-wijzigingen, CI valideert `flutter analyze`/`test`.
- Integratietests en `e2e/scenarios/` ongewijzigd.

### Bewust niet gedaan
Functionele wijzigingen, aanpassing van integratie-/e2e-tests, en de vier hierboven gemelde niet-veilig-herstelbare afwijkingen.
