# SF-779 - Nightly-jobs: subtasks.yaml + per-subtaak .md toevoegen (feed)

## Story

Nightly-jobs: subtasks.yaml + per-subtaak .md toevoegen (feed)

<!-- refined-by-factory -->

## Scope
Voeg voor elke bestaande nachtelijke job onder `.factory/nightly/<job>/` van deze repo configuratie toe zodat de factory de subtaken uit `subtasks.yaml` haalt en refine/plan overslaat. Het gaat om de zes jobs: `adr`, `consistency`, `documentation`, `integration-tests`, `quality`, `security`.

Per job:
- Voeg een `subtasks.yaml` toe met een **geordende** lijst subtaken; elke entry heeft een `type` en een `title`. De volолgorde in het bestand is de uitvoervolgorde. `subtasks.yaml` is volledig leidend: precies de opgesomde subtaken, niet meer en niet minder.
- Voeg voor elke AI-subtaak (types `development`, `review`, `test`, `summary`, `documentation`) een eigen beschrijvingsbestand toe met bestandsnaam die **exact** gelijk is aan de `title` + `.md` (bv. titel `Werk documentatie bij` → bestand `Werk documentatie bij.md`).
- Subtaken van type `merge` en `deploy` krijgen **geen** `.md`.

De standaardketen per job is: `development` → `review` → `test` → `summary` → `documentation` → `merge` → `deploy`. `subtasks.yaml` noemt alleen de typen; het projectgedrag (merge = handmatig, deploy = openshift-watch) komt uit `projects.yaml` in de software-factory-repo en hoeft hier niet herhaald te worden.

De `development`-`.md` van elke job wordt inhoudelijk gebaseerd op de bestaande `story.md` van diezelfde job (het feitelijke werk dat die job uitvoert). De overige AI-subtaak-`.md`'s (`review`, `test`, `summary`, `documentation`) beschrijven de generieke stap voor die job.

**Buiten scope / niet doen:**
- Geen wijziging aan broncode of aan de verwerkende/validerende factory-code (die staat in de software-factory-repo, aparte story).
- Geen wijziging aan bestaande `job.yaml`- of `story.md`-bestanden.
- Alleen tekst-/configbestanden toevoegen binnen `.factory/nightly/`.

## Acceptance criteria
- Voor elk van de zes jobmappen (`adr`, `consistency`, `documentation`, `integration-tests`, `quality`, `security`) bestaat een `.factory/nightly/<job>/subtasks.yaml`.
- Elke `subtasks.yaml` bevat een geordende lijst met per entry een `type` en een `title`; de volgorde weerspiegelt de uitvoervolgorde `development` → `review` → `test` → `summary` → `documentation` → `merge` → `deploy`.
- Alle gebruikte `type`-waarden vallen binnen de geldige set: `development`, `review`, `test`, `summary`, `documentation`, `merge`, `deploy`, `manual-approve`.
- Voor elke subtaak van type `development`, `review`, `test`, `summary`, `documentation` bestaat in dezelfde jobmap een `<title>.md` waarvan de bestandsnaam exact overeenkomt met de `title` uit `subtasks.yaml`.
- Voor subtaken van type `merge` en `deploy` bestaat géén `.md`-bestand.
- De `development`-`.md` van elke job dekt inhoudelijk het werk uit de bestaande `story.md` van diezelfde job (scope + randvoorwaarden herkenbaar).
- Er zijn geen wijzigingen buiten `.factory/nightly/` en geen wijzigingen aan bestaande bestanden binnen die map (alleen toevoegingen).
- De YAML is geldig (parsebaar) en de `.md`-bestanden zijn zelfstandig leesbaar.

## Aannames
- **Uniforme keten voor alle zes jobs.** De standaardketen wordt letterlijk toegepast op elke job, ook op de `documentation`-job (die krijgt dus ook een `development`- én een `documentation`-subtaak). De story schrijft de standaardketen expliciet "per job" voor; er is geen job-specifieke afwijking gevraagd.
- **Exacte subtaak-titels (niet voorgeschreven, hier vastgelegd op gedragsniveau).** Per type wordt een vaste, consistente Nederlandse titel gebruikt zodat de bestandsnamen voorspelbaar zijn:
  - `development`: de H1-titel van de betreffende `story.md` (job-specifiek), bv. `ADR-naleving herstellen`, `Consistentie: afwijkende patronen gladstrijken`, enz. — zodat de `.md` de bestaande story-inhoud draagt.
  - `review`: `Review de wijzigingen`
  - `test`: `Test de wijzigingen`
  - `summary`: `Vat de wijzigingen samen`
  - `documentation`: `Werk documentatie bij`
  - `merge`: `Merge` (geen `.md`)
  - `deploy`: `Deploy` (geen `.md`)
  Titels mogen door de developer consistent aangepast worden zolang bestandsnaam en `title` exact matchen en binnen een jobmap uniek zijn.
- **YAML-vorm.** `subtasks.yaml` is een lijst van mappings met de sleutels `type` en `title` (bv. een top-level `subtasks:`-lijst of een kale YAML-lijst); de exacte omhullende sleutelnaam wordt door de developer gekozen conform de conventie die de factory-code verwacht, met `type`/`title` per entry als harde eis uit de story.
- **`projects.yaml` niet in deze repo.** merge=handmatig en deploy=openshift-watch worden niet in deze repo gedefinieerd; `subtasks.yaml` noemt uitsluitend de typen.
- **`docs/factory/` is al compleet** (SF-220), dus er wordt geen extra docs-aanvulling-acceptatiecriterium toegevoegd.

## Eindsamenvatting

Ik heb voldoende context. Hier de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-779: Nightly-jobs: `subtasks.yaml` + per-subtaak `.md` toevoegen

### Wat is gebouwd
Voor elk van de zes bestaande nachtelijke jobs onder `.factory/nightly/` (`adr`, `consistency`, `documentation`, `integration-tests`, `quality`, `security`) is configuratie toegevoegd zodat de factory de subtaken rechtstreeks uit `subtasks.yaml` haalt en de refine/plan-fase overslaat.

Per job (6×):
- **`subtasks.yaml`** — een geordende `subtasks:`-lijst met exact zeven `type`/`title`-entries in de uitvoervolgorde: `development` → `review` → `test` → `summary` → `documentation` → `merge` → `deploy`. Volledig leidend: precies deze subtaken.
- **Vijf `.md`-bestanden** voor de AI-subtaken (`development`, `review`, `test`, `summary`, `documentation`), met een bestandsnaam die **exact** gelijk is aan de `title`.
- **`merge` en `deploy`** krijgen bewust géén `.md`.

In totaal 36 nieuwe bestanden (6 jobs × 6), plus het worklog. Alleen toevoegingen; geen bestaand bestand gewijzigd.

### Gemaakte keuzes
- **Uniforme keten voor alle zes jobs**, ook voor de `documentation`-job (die krijgt dus zowel een `development`- als een `documentation`-subtaak). De standaardketen is letterlijk toegepast.
- **Development-titel = exacte H1** uit de bestaande `story.md` van dezelfde job (o.a. `ADR-naleving herstellen`, `Consistentie: afwijkende patronen gladstrijken`, `Security: kwetsbaarheden vinden en oplossen`, incl. leestekens als `:`, `?`, apostrof). De inhoud van die `.md` is gebaseerd op die `story.md`, met de expliciete toevoeging dat het schrijven van (unit)tests bij de development-stap hoort.
- **Vaste generieke titels** voor de overige AI-subtaken: `Review de wijzigingen`, `Test de wijzigingen`, `Vat de wijzigingen samen`, `Werk documentatie bij` — job-specifiek en zelfstandig leesbaar.
- **YAML-vorm** volgt de `subtasks:`-conventie uit `docs/factory/agents/planner.md` (`{type, title}`-entries).
- **`projects.yaml`** (merge = handmatig, deploy = openshift-watch) leeft in de software-factory-repo en is hier niet herhaald.

### Wat is getest
Config-/tekst-only wijziging (geen broncode), dus geverifieerd via structurele validatie i.p.v. build/preview:
- Alle zes jobmappen hebben een `subtasks.yaml` met exact de zeven entries in de juiste volgorde; alle `type`-waarden binnen de geldige set.
- Bestandsnaam-matching geverifieerd: elke AI-subtaak heeft een `<title>.md` met exact matchende naam (incl. leestekens); `merge`/`deploy` hebben géén `.md`.
- `development`-titel == exacte H1 van de eigen `story.md` (6/6 match).
- `git status`: uitsluitend toevoegingen binnen `.factory/nightly/`; geen bestaande `job.yaml`/`story.md` aangeraakt.

Review (SF-780) en test (SF-781): **akkoord, geen blockers**.

### Bewust niet gedaan
- Geen wijziging aan broncode of aan de verwerkende/validerende factory-code (staat in de software-factory-repo, aparte story).
- Geen wijziging aan bestaande `job.yaml`- of `story.md`-bestanden.
- Geen `projects.yaml` in deze repo; `subtasks.yaml` noemt uitsluitend de typen.

### Aandachtspunt
PyYAML is niet aanwezig op de runner, dus de YAML is met een structurele parser gecontroleerd. Het definitieve parse-gedrag wordt door CI / de verwerkende factory-code gevalideerd.
