# SF-523 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope
Puur gedrag-neutraal consistentiewerk: spoor afwijkende patronen op in de codebase (backend Kotlin onder `newsfeedbackend/newsfeedbackend/` en de Flutter-frontends `frontend/` en `frontend-reader/`) en breng ze in lijn met de heersende norm. In scope:
- Afwijkende naamgeving, package-/laagstructuur, error-handling, logging-niveaus en API-conventies t.o.v. de meerderheidsnorm in de codebase.
- Plekken waar hetzelfde probleem op meerdere manieren is opgelost: trek ze gelijk naar het dominante patroon.

Leidend voor "de norm":
- `docs/factory/technical-spec.md` §Codeconventies en `specs/backend-technical-spec.md` (logging: SLF4J INFO/DEBUG/WARN/ERROR; geen comments tenzij WHY; `@Value` met expliciete `@param:`-use-site-target op constructor-`val`; lagenstructuur API→domain→infrastructure; Spring Modulith-modulegrenzen).
- `specs/openapi.yaml` als gezaghebbende bron voor API-conventies.
- Waar geen spec uitsluitsel geeft: het feitelijk dominante patroon in de codebase.

Buiten scope (melden via error/worklog, niet zelf wijzigen):
- Afwijkingen die het functionele gedrag kunnen veranderen.
- Bekende architectuur-/gedragsgevoelige afwijkingen die elders bewust zijn vastgelegd: Jackson-groupId `com.fasterxml.jackson` (spec-tekst noemt `tools.jackson`), cross-module `domain`/`infrastructure`-imports, en domeinmodellen die direct als HTTP-response worden gebruikt.
- Wijzigingen aan integratietests/e2e-scenario's of aan testverwachtingen om de build groen te krijgen.

## Acceptance criteria
- Wijzigingen zijn strikt gedrag-neutraal: het functionele gedrag is exact hetzelfde als voor de wijziging.
- `mvn test` (backend) blijft groen; bestaande unit-tests zijn niet aangepast om gedrag te maskeren.
- Geen enkele integratietest of e2e-scenario (de vangnetten) is gewijzigd. Als groen-krijgen een test-wijziging zou vereisen, wordt dat als gedragsverandering beschouwd → ga in error i.p.v. door te gaan.
- Elke gladgestreken afwijking is herleidbaar tot een expliciete norm (spec-conventie of aantoonbaar dominant codebase-patroon); de doorgevoerde richting volgt die norm.
- Bekende niet-veilige/architectuurgevoelige afwijkingen worden gerapporteerd in het worklog i.p.v. zelf hersteld.
- Een kleine of lege diff is een geldige uitkomst wanneer er geen veilige, gedrag-neutrale consistentieverbetering te maken is.
- `docs/factory/` wordt niet aangevuld (reeds compleet); deze story voegt daar geen werk aan toe.

## Aannames
- De story betreft uitsluitend interne code-consistentie, niet het uitbreiden of corrigeren van functionaliteit.
- "De norm" wordt bepaald door de meerderheid/spec, niet door persoonlijke voorkeur; bij twijfel of een patroon de norm is, wordt de wijziging niet doorgevoerd.
- Bij elke twijfel of een wijziging gedrag verandert, wordt de wijziging niet gedaan of gaat de developer in error (conform de randvoorwaarden).
- Scope omvat zowel de Kotlin-backend als beide Flutter-frontends; build-/CI-configuratie en migraties blijven ongemoeid tenzij puur cosmetisch en aantoonbaar gedrag-neutraal.
- `docs/stories/` (incl. worklog) is read-only input voor de afweging, geen wijzigingsdoel.

## Eindsamenvatting

Ik heb `.task.md` en het worklog (`SF-523-worklog.md`, incl. tester-verificatie SF-525) gelezen. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-523: Consistentie: afwijkende patronen gladstrijken (nightly)

### Wat is gebouwd
Een gedrag-neutrale consistentie-pass over backend (`newsfeedbackend/`) en beide Flutter-frontends (`frontend/`, `frontend-reader/`). **Uitkomst: een bewust lege code-diff** — de codebase bleek bij verificatie al volledig conform de norm. Conform de acceptance criteria is een lege diff een geldige uitkomst. De enige bestandswijziging is het worklog zelf.

### Belangrijkste keuzes
- **Geen "fix om te fixen":** waar geen veilige, aantoonbaar gedrag-neutrale verbetering bestond, is bewust niets gewijzigd. Dit bevestigt de consolidatie uit eerdere nightlies (SF-392, SF-456, SF-502).
- **Architectuurgevoelige afwijkingen gemeld i.p.v. gefixt** (buiten scope, zouden gedrag veranderen):
  - `SettingsController` zonder klasse-`@RequestMapping` — bedient bewust meerdere prefixes (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`); een base-path zou URL's wijzigen.
  - Jackson-groupId `com.fasterxml.jackson` — spec is inmiddels aligned, geen afwijking meer.
  - Cross-module `domain`/`infrastructure`-imports en domeinmodellen direct als HTTP-response — bekende §4-afwijkingen; herstel is een gedragsgevoelige refactor.

### Geverifieerd conform (norm)
- `@param:Value` use-site-target: 21× toegepast, 0 kale `@Value` op constructor-`val`; 2 terechte gedocumenteerde uitzonderingen (`@Bean`-param + plain param zonder `val`).
- Logger-declaratie 39/39 conform; external_call WARN-fallback 10/10 conform.
- Alle 13 `@RestController` onder `*/api/`; geen inline DTO `data class` in controllers.
- Frontends: 0 `print(`, geen TODO/FIXME-restanten.

### Wat is getest
- **Backend `mvn test`: BUILD SUCCESS, 25 unit-tests groen**, geen tests aangepast, geen integratie-/e2e-scenario's geraakt.
- Tester (SF-525) heeft alle claims via grep/code-inspectie op `main...HEAD` herbevestigd → **tested-ok**.

### Bewust niet gedaan
- Geen code-/implementatiewijzigingen (geen veilige gladstrijkactie beschikbaar).
- Geen test-, build/CI- of migratiewijzigingen.
- Frontends niet onderling gelijkgetrokken (twee aparte apps; verschillen zijn cross-app, geen normafwijking binnen één app).
- Browser-/preview-test overgeslagen: geen gedrag gewijzigd om visueel te verifiëren.

---
