# SF-779 - Worklog

Story-context bij eerste pickup:
subtasks.yaml + per-subtaak .md per nightly-job toevoegen

Voeg voor elk van de zes bestaande jobmappen onder .factory/nightly/ (adr, consistency, documentation, integration-tests, quality, security) toe: (1) een subtasks.yaml met een geordende lijst mappings van type+title in de uitvoervolgorde development -> review -> test -> summary -> documentation -> merge -> deploy (volledig leidend: exact deze zeven entries, alle types binnen de geldige set, YAML parsebaar); (2) per AI-subtaak (development, review, test, summary, documentation) een <title>.md waarvan de bestandsnaam exact gelijk is aan de title + .md en uniek binnen de jobmap; merge en deploy krijgen geen .md.

## Stappenplan
- [x] Issue en target-docs lezen (.task.md, .factory/nightly/*, docs/factory/agents/planner.md)
- [x] Per job een subtasks.yaml + per AI-subtaak een <title>.md toevoegen
- [x] Structuur valideren (YAML-vorm, type-set, volgorde, bestandsnaam == title, merge/deploy géén .md)
- [x] Story-log bijwerken met resultaat

## Done / rationale

Voor elk van de zes jobmappen (`adr`, `consistency`, `documentation`, `integration-tests`,
`quality`, `security`) toegevoegd:
- `subtasks.yaml` met een top-level `subtasks:`-lijst van `type`/`title`-mappings. Deze YAML-vorm
  volgt de conventie die de factory-planner gebruikt (`docs/factory/agents/planner.md`:
  `{"type":...,"title":...}`-entries). De volgorde in het bestand is de uitvoervolgorde:
  `development` → `review` → `test` → `summary` → `documentation` → `merge` → `deploy` (exact deze
  zeven entries; volledig leidend).
- Vijf `.md`-bestanden voor de AI-subtaken (`development`, `review`, `test`, `summary`,
  `documentation`), met bestandsnaam **exact** gelijk aan de `title` + `.md`.
- `merge` en `deploy` krijgen bewust géén `.md`.

Titelkeuze:
- `development` = de exacte H1 uit de bestaande `story.md` van dezelfde job (job-specifiek), en de
  inhoud van die `.md` is gebaseerd op die `story.md` (scope + randvoorwaarden herkenbaar
  overgenomen, met een expliciete regel dat het schrijven van (unit)tests bij de development-stap
  hoort). H1's: `ADR-naleving herstellen`, `Consistentie: afwijkende patronen gladstrijken`,
  `Documentatie-check: docs en code in sync?`, `Integratietests: ontbrekende scenario's toevoegen`,
  `Code-kwaliteit verbeteren`, `Security: kwetsbaarheden vinden en oplossen`.
- Generieke AI-subtaken met vaste titels: `Review de wijzigingen`, `Test de wijzigingen`,
  `Vat de wijzigingen samen`, `Werk documentatie bij`. Elk `.md` is zelfstandig leesbaar en
  beschrijft de generieke stap voor die job.
- `merge` → title `Merge`, `deploy` → title `Deploy` (geen `.md`).

`projects.yaml` (merge=handmatig, deploy=openshift-watch) leeft in de software-factory-repo en is
hier niet herhaald; `subtasks.yaml` noemt uitsluitend de typen.

## Tests / validatie

Config-only wijziging (geen broncode), dus geverifieerd met een validatiescript i.p.v. mvn/flutter:
- Elk van de zes jobmappen heeft een `subtasks.yaml`.
- Elke `subtasks.yaml` bevat exact de zeven entries in de voorgeschreven volgorde.
- Alle `type`-waarden vallen binnen de geldige set (`development`, `review`, `test`, `summary`,
  `documentation`, `merge`, `deploy`, `manual-approve`).
- Voor elke AI-subtaak bestaat een `<title>.md` met exact matchende bestandsnaam; titels zijn
  uniek binnen de jobmap.
- Voor `merge` en `deploy` bestaat géén `.md`.
- `git status`: uitsluitend toevoegingen binnen `.factory/nightly/`; geen bestaande
  `job.yaml`/`story.md` gewijzigd.

PyYAML is niet aanwezig op de runner (bekend, zie agent-tip `pnf-runner-no-pip`); de YAML-vorm is
daarom met een structurele parser gecontroleerd. CI/de verwerkende factory-code valideert het
definitieve parse-gedrag.

## Review (SF-780) — 2026-07-05

[info] Volledige story-diff `git diff main...HEAD` gereviewd: 36 nieuwe bestanden (6 jobs × 6) + worklog, alle status A (geen bestaand bestand gewijzigd, niets buiten `.factory/nightly/`).
[info] 6× `subtasks.yaml`: geldige YAML, `subtasks:`-lijst van {type,title} in correcte volgorde development→review→test→summary→documentation→merge→deploy; alle types binnen de geldige set; sluit aan op de `subtasks`-conventie uit `docs/factory/agents/planner.md`.
[info] Development-`.md` bestandsnamen == exacte H1 uit de eigen `story.md` van elke job (incl. `:`/`?`/apostrof); inhoud dekt scope + randvoorwaarden van de story herkenbaar.
[info] Generieke AI-`.md` (review/test/summary/documentation) aanwezig per job, job-specifiek en zelfstandig leesbaar; `merge`/`deploy` hebben terecht géén `.md`.
[info] Worklog vrij van JSON-artefacten (`agent_tips_update`/`phase`).
Akkoord — geen blockers/bugs.

## Test (SF-781) — 2026-07-05

[info] Story is config/tekst-only binnen `.factory/nightly/`; geen broncode gewijzigd → geen build/preview-run nodig. `git diff main..HEAD`: 36 nieuwe bestanden (6 jobs × subtasks.yaml + 5 .md) + worklog, alle status A. Niets buiten `.factory/nightly/` behalve worklog; geen bestaande `job.yaml`/`story.md` gewijzigd.
[info] Structurele validatie (eigen parser, PyYAML afwezig op runner): elke `subtasks.yaml` heeft `subtasks:`-lijst met per entry `type`+`title` in volgorde development→review→test→summary→documentation→merge→deploy; alle 6 jobs identieke keten; alle types binnen geldige set.
[info] Bestandsnaam-matching geverifieerd voor alle 6 jobs: elke AI-subtaak (development/review/test/summary/documentation) heeft `<title>.md` met exact matchende naam (incl. `:`, `?`, apostrof); `merge`/`deploy` hebben géén `.md`.
[info] `development`-title == exacte H1 van de eigen `story.md` per job (6/6 match); dev-`.md` inhoud dekt scope + randvoorwaarden van de story herkenbaar (identiek aan story + toevoeging dat de subtaak het ontwikkelwerk incl. tests omvat).
[info] Generieke `.md` (review/test/summary/documentation) per job job-specifiek en zelfstandig leesbaar.
Akkoord — alle acceptatiecriteria voldaan, geen blockers.
