# SF-608 - Worklog

Story-context bij eerste pickup:
Consistentie-pass: afwijkende patronen gladstrijken

Voer een gedrag-neutrale consistentie-pass uit over backend (newsfeedbackend/newsfeedbackend/) en de Flutter-frontends frontend/ en frontend-reader/. Breng afwijkende naamgeving, klasse-/bestandsstructuur, error-handling, logging-niveaus en API-conventies in lijn met de norm: docs/factory/technical-spec.md §Codeconventies, specs/backend-technical-spec.md en specs/openapi.yaml. Trek plekken waar hetzelfde probleem op meerdere manieren is opgelost gelijk naar één bestaand dominant patroon. Functioneel gedrag blijft exact gelijk; bij twijfel niet wijzigen of in error gaan. Pas integratietests en e2e-scenario's (e2e/scenarios/) NIET aan - is dat nodig om groen te worden, ga dan in error. Documenteer bekende niet-veilig gladstrijkbare afwijkingen (Jackson com.fasterxml.jackson vs tools.jackson, cross-module domain/infrastructure-imports, domeinmodel-als-HTTP-response) in het worklog i.p.v. ze te fixen. Een lege/kleine diff is een geldige uitkomst. Verifieer dat mvn test (vereist PNF_DATABASE_URL) en de Flutter-tests onveranderd groen blijven. Sluit af met een review-stap.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## SF-615 (developing) — consistentie-verificatie 2026-06-28

### Uitkomst: geen code-diff (codebase reeds geconsolideerd)

Net als bij de vorige nightly consistentie-/ADR-runs (SF-392, SF-456/457, SF-502/504,
SF-586/588) is de codebase al in lijn met de veilig-herstelbare conventies. Een lege
code-diff is hier de geldige uitkomst (AC: "Een lege of kleine diff is een geldige
uitkomst wanneer er geen veilige, gedrag-neutrale verbetering te maken is").

### Geverifieerd conform (backend `newsfeedbackend/newsfeedbackend/`)

Via grep over `src/main`:
- **`@param:Value`**: 21 stuks met use-site-target `@param:`, 0 kale `@Value` op een
  `val`-property. De 2 gedocumenteerde uitzonderingen staan terecht zonder `@param:`:
  - `podcast_source/infrastructure/PodcastAsyncConfig.kt` (`@Value` op `@Bean`-methodeparameter — `@param:` is compilefout)
  - `podcast_source/domain/PodcastTranscriptWorker.kt` (`@Value` op plain constructor-param zonder `val`/`var` — `@param:` geeft redundant-target-warning)
- **Logger**: 39/39 `private val log = LoggerFactory.getLogger(javaClass)`; geen afwijkende logger-declaratie.
- **external_call-fallback**: 10/10 `log.warn("[Tag] could not log external_call: {}", e.message)` (WARN-niveau, consistent tag-prefix).
- **DTO-plaatsing**: geen inline `data class` in `*Controller.kt` (request-DTO's in `module/api/dto/`, sinds SF-437).
- **KDoc-positie**: geen KDoc ná Spring-stereotype-annotatie; conventie KDoc vóór annotatie wordt overal gevolgd.
- **OpenAPI**: geen `openapi-generator`-plugin in `pom.xml`; controllers blijven handgeschreven `@RestController`.
- **Jackson**: alle imports `com.fasterxml.jackson` (36 treffers), 0 `tools.jackson` — consistent met pom.xml én met de inmiddels aligned spec (geen drift meer).

### Geverifieerd conform (frontends)

- `frontend/` en `frontend-reader/` zijn twee verschillende apps (volledige Riverpod-app
  met `screens/`/`providers/`-structuur vs. minimale reader met flatte `lib/`). Cross-app
  verschillen (bv. `_Tab` in reader vs. `_FeedTab`/`_RssTab` in frontend) zijn geen norm
  binnen één app en worden niet gelijkgetrokken.
- Geen `print(...)` debug-logging in `frontend/lib` of `frontend-reader/lib` (0 treffers) —
  logging-conventie consistent.

### Niet-fixbare afwijkingen (gemeld, NIET gewijzigd — vooraf erkend in .task.md Aannames)

1. **Jackson `com.fasterxml.jackson` i.p.v. `tools.jackson`**: de code gebruikt overal
   `com.fasterxml.jackson` (pom.xml + imports) en compileert/test zo groen. Migratie naar
   `tools.jackson` (Jackson 3) is een risicovolle dependency-/package-change, geen veilige
   mechanische fix. (De spec is op dit punt overigens al naar `com.fasterxml` aligned.)
2. **Cross-module imports tussen `domain`/`infrastructure`**: meerdere bestaande
   Spring Modulith-overtredingen (o.a. `common/admin`→`auth.infrastructure.UserRepository`,
   `events.domain`→`request.infrastructure.TavilyClient`/`podcast_source.infrastructure.WhisperClient`,
   `settings.api`→`podcast_source.infrastructure.PodcastFeedFetcher`). Een
   `ApplicationModules.verify()`-test bestaat niet en toevoegen zou de build laten falen;
   dit zijn architecturale refactors, geen gedrag-neutrale fixes.
3. **Domeinmodellen direct als HTTP-response**: controllers retourneren domeinmodellen
   (met `@JsonProperty`) rechtstreeks i.p.v. response-DTO's (backend-spec §4-violation).
   Mechanisch herstel zou nieuwe DTO's en mapping vereisen = niet gedrag-neutraal gegarandeerd.
4. **`settings/api/SettingsController.kt` zonder klasse-`@RequestMapping`**: bedient drie
   prefixes (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`). Een base-path op
   klasse-niveau zou de URL's wijzigen = gedragsverandering.

### Tests

- `mvn test` in `newsfeedbackend/newsfeedbackend/`: **BUILD SUCCESS — 28 tests, 0 failures,
  0 errors** (RssFetcherImageUrlTest 6, AiPricingPropertiesTest 4, ApiRequestDtoContractTest 6,
  PodcastScriptParserTest 9, VideoAudioDownloaderArgsTest 3). De aanwezige tests zijn pure
  unit-tests en draaien zonder `PNF_DATABASE_URL`.
- Flutter-tests niet lokaal gedraaid: de developer-runner heeft geen flutter/dart-binary;
  geen frontend-wijzigingen, dus geen risico. CI valideert `flutter analyze`/`flutter test`.
- Integratietests en `e2e/scenarios/` ongewijzigd.

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Consistentie-pass uitgevoerd door verificatie via grep over backend + frontends; geen
  veilige gedrag-neutrale wijziging gevonden → lege code-diff, bevindingen gedocumenteerd.

## SF-615 (reviewed) — review 2026-06-28

Reviewer-verificatie van de handover-claims tegen de repo (alle bevestigd):
- Diff-scope: enkel story-log + dit worklog; geen code-/test-/e2e-wijzigingen.
- `@param:Value` = 21; kale `@Value` enkel de 2 gedocumenteerde uitzonderingen.
- Logger 39/39 conform; `tools.jackson` = 0, `com.fasterxml.jackson` = 36; geen inline
  `data class` in `*Controller.kt`; `print(` = 0 in beide frontend-`lib/`-dirs.
- Geen `agent_tips_update`/`phase`-JSON-artefacten in de gecommitte docs.
- Vier niet-fixbare afwijkingen correct gemeld i.p.v. gefixt (conform Aannames).

[info] Empty-diff is een geldige uitkomst per AC. Geen blockers/bugs. → akkoord.
