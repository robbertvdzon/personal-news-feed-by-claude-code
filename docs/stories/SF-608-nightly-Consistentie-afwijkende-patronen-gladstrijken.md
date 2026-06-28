# SF-608 — Consistentie: afwijkende patronen gladstrijken

Gedrag-neutrale consistentie-pass over backend (`newsfeedbackend/newsfeedbackend/`) en de
twee Flutter-frontends (`frontend/`, `frontend-reader/`). Norm: `docs/factory/technical-spec.md`
§Codeconventies, `specs/backend-technical-spec.md`, `specs/openapi.yaml`.

## Stappenplan

- [x] Issue + factory-docs + specs gelezen
- [x] Backend-conventies geverifieerd via grep (geen veilige wijziging gevonden)
- [x] Frontends geverifieerd (twee verschillende apps; geen norm-afwijking binnen één app)
- [x] `mvn test` gedraaid — groen (28 tests)
- [x] Niet-fixbare afwijkingen gedocumenteerd in worklog
- [x] Story-log bijgewerkt

## Wat is gedaan en waarom

Conform de vorige nightly consistentie-/ADR-runs (SF-456/457, SF-502/504, SF-586/588) is
de codebase al geconsolideerd op alle veilig-herstelbare conventies. Verificatie via grep
(zie `docs/stories/worklog/SF-608-worklog.md` voor de volledige meting):

- `@param:Value` 21x + 2 gedocumenteerde uitzonderingen; geen kale `@Value` op `val`.
- Logger 39/39 `private val log = LoggerFactory.getLogger(javaClass)`.
- external_call-fallback 10/10 op WARN met consistent tag-prefix.
- Geen inline request-DTO in `*Controller.kt`; KDoc vóór annotatie; geen openapi-generator.
- Jackson overal `com.fasterxml.jackson` (consistent met pom.xml en de aligned spec).
- Frontends: geen `print()`-logging; structuurverschillen zijn cross-app, geen norm.

Er is geen veilige, gedrag-neutrale wijziging te maken → **lege code-diff** (een geldige
uitkomst volgens de acceptatiecriteria). De vier vooraf erkende niet-fixbare afwijkingen
(Jackson-groupId, cross-module imports, domeinmodel-als-HTTP-response, SettingsController
zonder klasse-`@RequestMapping`) zijn gemeld in het worklog en niet gewijzigd, omdat
herstel een gedragsrisico of een architecturale refactor zou zijn.

`mvn test`: BUILD SUCCESS, 28 tests, 0 failures/errors. Integratietests en `e2e/scenarios/`
ongewijzigd.
