# SF-698 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope
Gedrag-neutrale consistentie-pass over de codebase: zoek afwijkende patronen en breng ze in lijn met de bestaande norm in de codebase. Dit is puur refactor-/opschoonwerk, geen functionele wijziging.

In scope:
- Backend Kotlin: `newsfeedbackend/newsfeedbackend/`
- Frontends: `frontend/` en `frontend-reader/`
- Soort afwijkingen: naamgeving, code-/pakketstructuur, error-handling, logging(-niveaus), en API-conventies die afwijken van de rest van de codebase.
- Plekken waar hetzelfde probleem op verschillende manieren is opgelost: trek deze gelijk naar één patroon.

Norm (gezaghebbende bronnen, in volgorde):
- `docs/factory/technical-spec.md` §Codeconventies (o.a. geen comments tenzij WHY, `@param:`-target bij `@Value`).
- `specs/backend-technical-spec.md` (lagen API → domain → infrastructure, Spring Modulith-modulegrenzen, DTO-regels, logging).
- `specs/openapi.yaml` als gezaghebbende bron voor het API-contract.

Buiten scope:
- Functionele gedragswijzigingen van welke aard dan ook.
- Het aanpassen van integratietests en de e2e-scenario's in `e2e/scenarios/` (deze zijn het vangnet).
- Aanvullen van `docs/factory/` (is compleet sinds SF-220) — geen docs-criterium nodig.
- Bekende, niet-veilige afwijkingen die niet zonder gedragsrisico te corrigeren zijn (zie Aannames): deze worden gemeld, niet zelf hersteld.

## Acceptance criteria
- Afwijkende patronen in backend en/of beide frontends zijn opgespoord en in lijn gebracht met de norm uit de genoemde bronnen.
- Het functionele gedrag is exact gelijk gebleven; er zijn geen gedragswijzigingen doorgevoerd.
- `mvn test` slaagt onverminderd groen (vereist `PNF_DATABASE_URL`).
- Geen enkele integratietest of e2e-scenario (`e2e/scenarios/`) is gewijzigd. Als groen krijgen een wijziging aan een integratietest zou vereisen, wordt dat als gedragswijziging beschouwd → de taak gaat in error i.p.v. de test aan te passen.
- Bij twijfel of een wijziging gedrag verandert: de wijziging wordt niet doorgevoerd, of de taak gaat in error.
- Een kleine of lege diff is een geldige uitkomst wanneer de codebase al consistent is.
- Niet-veilig te corrigeren afwijkingen worden expliciet gemeld (in worklog/error) in plaats van doorgevoerd.

## Aannames
- Story behoort tot de nightly consistentie-familie (identiek aan SF-608/SF-523/SF-343); storybron is `.factory/nightly/consistency/story.md`.
- "Consistent maken" betekent: het minst afwijkende/meest voorkomende patroon in de codebase is de norm; uitschieters worden daarnaartoe gelijkgetrokken, niet andersom.
- De volgende bekende afwijkingen worden als niet-veilig beschouwd (corrigeren kan gedrag/serialisatie/architectuur raken) en worden gemeld i.p.v. zelf hersteld: Jackson-groupId `com.fasterxml.jackson` vs. spec `tools.jackson`; cross-module imports tussen `domain` en `infrastructure`; domeinmodellen die direct als HTTP-response worden teruggegeven.
- `docs/factory/` is volledig (SF-220); er wordt geen acceptatiecriterium voor docs-aanvulling toegevoegd.
- Als een schijnbaar inconsistent patroon bij nader inzien een bewuste, functioneel relevante keuze is, blijft het ongemoeid.

## Eindsamenvatting

Hieronder de eindsamenvatting voor de PO.

---

## SF-698 — Consistentie: afwijkende patronen gladstrijken

### Wat is gebouwd
Een gedrag-neutrale consistentie-pass (nightly) over de backend (`newsfeedbackend/`) en beide frontends (`frontend/`, `frontend-reader/`). Uitkomst: **de codebase is al consistent** — er was geen veilige, gedrag-neutrale wijziging meer open. De story levert daarom een **lege code-diff** op; de enige toegevoegde wijziging is het worklog. Dat is conform het acceptatiecriterium dat een kleine of lege diff een geldige uitkomst is.

### Gemaakte keuzes
- Het meest voorkomende patroon in de codebase is als norm gehanteerd; uitschieters worden daarnaartoe getrokken, niet andersom.
- Alleen veilige, gedrag-neutrale wijzigingen waren toegestaan. Omdat alles al conform bleek, is bewust niets aangepast in plaats van cosmetische wijzigingen te forceren.
- Integratietests en `e2e/scenarios/` zijn als vangnet ongemoeid gelaten.

### Geverifieerd conform
- Logger: 39/39 `private val log = LoggerFactory.getLogger(javaClass)`, geen afwijkingen.
- `@Value`: overal `@param:Value`; de 2 kale `@Value` zijn de gedocumenteerde uitzonderingen (`PodcastAsyncConfig` `@Bean`-param, `PodcastTranscriptWorker` plain ctor-param) die juist niet `@param:` mogen krijgen.
- external_call-fallback: 10/10 uniform `log.warn(... could not log external_call ...)`.
- Geen inline `data class`-DTO's in `*Controller.kt`; DTO's staan in `module/api/dto/`. Geen kale `print(`-logging in de frontends. Jackson op `com.fasterxml.jackson` (geen drift).

### Getest
- Backend `mvn test`: **BUILD SUCCESS**, 28 unit-tests groen (geen `PNF_DATABASE_URL` nodig — unit-tests raken de DB niet).
- Geen enkele integratietest of e2e-scenario gewijzigd.
- Tester-fase (SF-700): alle worklog-claims hard gereproduceerd via grep; diff t.o.v. `main` op de bronmappen is leeg. Status: **tested**.

### Bewust niet gedaan (gemeld, niet hersteld)
Deze afwijkingen zijn niet zonder gedrags-/serialisatie-/architectuurrisico te corrigeren en zijn daarom gemeld i.p.v. gefixt:
1. Jackson-groupId `com.fasterxml.jackson` vs. historische spec `tools.jackson` (sinds SF-502 is de spec zelf al op `com.fasterxml`; migreren blijft een risicovolle dependency-change).
2. Cross-module imports tussen interne `domain`/`infrastructure`-klassen (architecturale refactor, niet mechanisch gedrag-neutraal).
3. Domeinmodellen (o.a. `RssItem`) met `@JsonProperty` die direct als HTTP-response worden geserialiseerd (raakt het API-contract).
4. `SettingsController.kt` zonder klasse-`@RequestMapping` (bedient meerdere prefixes: `/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`); een base-path toevoegen zou URLs wijzigen.

---
