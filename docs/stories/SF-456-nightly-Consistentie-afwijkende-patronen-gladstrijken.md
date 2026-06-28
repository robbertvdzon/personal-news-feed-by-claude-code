# SF-456 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope

Gedrag-neutrale consistentiepass over de codebase (Kotlin-backend in `newsfeedbackend/newsfeedbackend/` en de Flutter-frontends `frontend/` en `frontend-reader/`). Doel: afwijkende patronen gelijktrekken naar de bestaande norm, zónder functioneel gedrag te wijzigen.

In scope — uitlijnen waar code aantoonbaar afwijkt van de in de codebase dominante conventie:
- **Naamgeving & structuur**: pakket-/klasse-/bestandsnaamgeving en de lagenstructuur per Spring-Modulith-module (`ModuleService` interface → `api/` (+`dto/`) → `domain/` → `infrastructure/`) zoals beschreven in `technical-spec.md`.
- **Error-handling**: dezelfde fout op één manier afhandelen i.p.v. meerdere; aansluiten bij het dominante patroon in de module.
- **Logging**: SLF4J-niveaus conform conventie (INFO job-start/einde, DEBUG externe API-calls, WARN herstelbaar, ERROR niet-herstelbaar).
- **API-conventies**: controllers consistent met het handmatige contract `specs/openapi.yaml` (geen code-generatie); uniforme `@RequestMapping`/`@GetMapping`-stijl en DTO-gebruik.
- **Kotlin-idioom**: o.a. `@param:`-use-site-target op `@Value`-constructor-properties (`val`) conform `technical-spec.md` §Codeconventies, mét de daar genoemde uitzonderingen.
- **Duplicate-oplossingen**: plekken waar hetzelfde probleem op meerdere manieren is opgelost, terugbrengen tot de norm.

Buiten scope:
- Elke wijziging die functioneel gedrag verandert.
- Wijzigen van integratietests/feature-bestanden of openapi-contract om het werk groen te krijgen.
- Het zelf "herstellen" van structurele/architecturale afwijkingen waarvan onzeker is of gedrag verandert (bv. cross-module imports tussen `domain`/`infrastructure`, domeinmodellen direct als HTTP-response). Deze worden gemeld, niet stilzwijgend aangepast.
- Nieuwe features, dependency-upgrades, DB-migraties.

## Acceptance criteria

1. Alle wijzigingen zijn puur consistentie; het functionele gedrag blijft exact gelijk.
2. `mvn test` (backend) slaagt; bestaande Flutter-tests blijven slagen.
3. Geen enkele integratietest is gewijzigd. Als groen krijgen een testwijziging zou vereisen, wordt het werk niet gedaan en gaat de developer in **error** met concrete onderbouwing.
4. Bij twijfel of een wijziging gedrag verandert, wordt de wijziging niet gedaan (overgeslagen of error), niet "voor de zekerheid" toch.
5. Doorgevoerde uitlijningen verwijzen naar de norm-bron (`technical-spec.md` / `specs/backend-technical-spec.md` / dominante patroon in de codebase).
6. Het worklog `docs/stories/worklog/SF-456-worklog.md` bevat per uitgevoerde uitlijning kort wat en waarom, plus eventuele bewust níet-aangepakte (gemelde) afwijkingen.
7. Een kleine of zelfs lege code-diff is een geldige uitkomst wanneer de codebase al consistent is.

## Aannames

- "De norm" = de dominante conventie zoals vastgelegd in `docs/factory/technical-spec.md` en `specs/backend-technical-spec.md`; waar docs zwijgen, het patroon dat in de codebase het meest voorkomt.
- De Jackson-groupId is reeds consistent `com.fasterxml.jackson` (per `technical-spec.md` §Codeconventies) en vergt geen actie.
- "Integratietests" omvat de Cucumber/WireMock-suite; die is momenteel nog niet in de repo aanwezig, dus het vangnet betreft feitelijk de bestaande unit-tests plus het verbod om eventueel aanwezige integratietests aan te raken.
- Dit is een nightly/silent run: bij echte onduidelijkheid gaat de developer in error i.p.v. te wachten op interactie.
- De `geen-comments`-conventie blijft gerespecteerd (comments alleen bij niet-vanzelfsprekende WHY); class-level KDoc valt daar niet onder.

## Eindsamenvatting

Hieronder de eindsamenvatting voor de PO van story **SF-456 — nightly: Consistentie: afwijkende patronen gladstrijken**.

---

## Eindsamenvatting SF-456

### Uitkomst in één zin
Een gedrag-neutrale consistentiepass over backend en frontends is uitgevoerd met als bewuste en correct onderbouwde uitkomst een **lege code-diff**: de codebase is op alle in-scope categorieën al consistent.

### Wat is gebouwd
- **Geen code- of testwijzigingen.** De enige toevoegingen aan de branch zijn documentatie: de story-log `docs/stories/SF-457-consistentie-uitlijningen.md` en het worklog `docs/stories/worklog/SF-456-worklog.md`.
- De pass scande de Kotlin-backend (`newsfeedbackend/`) en beide Flutter-apps (`frontend/`, `frontend-reader/`) op afwijkingen t.o.v. de norm uit `technical-spec.md` en het dominante codebase-patroon.

### Geverifieerd al-conform (geen actie nodig)
Per categorie is de norm vastgesteld en gericht op afwijkingen gezocht; alles bleek reeds uitgelijnd, mede door eerdere nightly-passes (SF-392, SF-437):
- **`@Value` use-site-target**: alle constructor-properties zijn `@param:Value`; de 2 resterende zonder target zijn de gedocumenteerde uitzonderingen.
- **Lagenstructuur Spring-Modulith**: Controller→`api/`, ServiceImpl/domein→`domain/`, Repository→`infrastructure/`, request-DTO's→`api/dto/`.
- **Logger-declaratie**: 39/39 klassen identiek (`LoggerFactory.getLogger(javaClass)`).
- **Controller-mappingstijl**: overal `@GetMapping`/`@PostMapping`, nooit `@RequestMapping(method=)`.
- **Error-handling `external_call`-fallback**: 10/10 clients genormeerd.
- **KDoc-positie & Jackson groupId** (`com.fasterxml.jackson`): consistent.

### Gemaakte keuzes — bewust níet aangepakt (gemeld, niet stilzwijgend gewijzigd)
1. **`SettingsController` mist klasse-niveau `@RequestMapping`** — bedient meerdere prefixes (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`). Eén base-path toevoegen zou endpoint-URL's wijzigen → gedragswijziging. Structurele afwijking, geen mechanische fix (AC #1/#4).
2. **`frontend-reader` `_Tab`-class** wijkt af van `_FeedTab`/`_RssTab` in `frontend/` — aparte app, privé-class, geen dominante norm binnen die app → stijlvoorkeur, geen consistentie-eis.
3. **Bestaande architecturale modulith-violations** (cross-module imports, domeinmodellen direct als HTTP-response) — buiten scope met gedragsrisico; ongewijzigd gemeld.

### Wat is getest
- **Backend `mvn test`**: BUILD SUCCESS — 25 tests, 0 failures/errors; geen compiler-warnings.
- **Tester-verificatie**: diff bevestigd als doc-only (geen `.kt`, Dart, tests of infra); alle "al-conform"- en "bewust-niet-aangepakt"-claims via grep/inspectie tegen de feitelijke codebase nagerekend en kloppend bevonden. **Goedgekeurd.**
- Geen integratietests of `openapi.yaml` aangeraakt. Flutter ongewijzigd; CI valideert de build.

### Conclusie voor de PO
De story is succesvol afgerond. De lege code-diff is conform AC #7 een geldige uitkomst: er was geen veilige, gedrag-neutrale uitlijning meer over. Functioneel gedrag is gegarandeerd ongewijzigd. Drie afwijkingen zijn expliciet gemeld voor eventuele toekomstige (gerichte) opvolging, omdat fixen daarvan gedragsrisico of architecturale keuzes met zich meebrengt.

---
