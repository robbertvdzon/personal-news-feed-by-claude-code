# SF-371 - Worklog

Story-context bij eerste pickup:
ADR-conventies nalopen en veilige afwijkingen herstellen

Loop de ADR-equivalente conventiebronnen (specs/backend-technical-spec.md en docs/factory/technical-spec.md) na tegen de backend (newsfeedbackend/) en de Flutter-frontends (frontend/, frontend-reader/). Herstel ALLEEN puur mechanische, gedrag-neutrale afwijkingen. Een lege of minimale diff is een geldige uitkomst.

## Stappenplan

- [x] read issue and target docs
- [x] ADR-equivalente conventiebronnen nalopen tegen de code
- [x] veilige (mechanische, gedrag-neutrale) afwijkingen herstellen — geen gevonden
- [x] baseline-build/tests draaien (`mvn test`)
- [x] update story-log met resultaten en rationale

## Uitgevoerde controle (developing, SF-373)

Gecontroleerde conventies uit `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md`:

### 1. OpenAPI Generator-plugin in pom.xml — CONFORM
`grep "openapi-generator" pom.xml` geeft geen treffers. Er is geen
`target/generated-sources/openapi/`-map; controllers zijn handgeschreven
`@RestController`-klassen. Geen actie nodig.

### 2. `@Value` met expliciet `@param:`-use-site-target — CONFORM
Alle `@Value` op primary-constructor-`val` properties hebben al `@param:Value(...)`.
De enige twee `@Value` zónder `@param:` zijn de gedocumenteerde uitzonderingen
(spec §codeconventies):
- `podcast_source/infrastructure/PodcastAsyncConfig.kt:13` — `@Value` op een
  `@Bean`-methodeparameter (daar is `@param:` een compilefout).
- `podcast_source/domain/PodcastTranscriptWorker.kt:47` — `@Value` op een plain
  constructor-parameter zónder `val`/`var` (daar geeft `@param:` "redundant target").

Beide moeten conform de spec zónder target blijven. Geen actie nodig.

### 3. Controllers: geen business logic, geen directe repository-aanroepen — CONFORM
Geen enkele `*Controller.kt` importeert of gebruikt een `*Repository`
(grep op `Repository`/`repository` in `*/api/*Controller.kt` is leeg). De
controllers delegeren naar de publieke service-interfaces.

### 4. Inline comments ("geen comments tenzij WHY niet-vanzelfsprekend") — CONFORM
Alle ~267 inline `//`-comments in de backend zijn WHY-comments: ze leggen
edge-cases, fallback-ketens, idempotency, AC-verwijzingen en design-rationale
vast (bv. `common/Exceptions.kt`, `common/JacksonConfig.kt:22-25`,
`podcast_source/domain/PodcastEpisodeProcessor.kt`). Er zijn geen overbodige
comments die louter de code herhalen. Class-level KDoc valt buiten de conventie.
Geen actie nodig.

### 5. Flutter-frontends — CONFORM (binnen de spec-conventies)
De ADR-equivalente conventiebronnen leggen voor de frontend alleen Riverpod 2.x
als state-management vast; `frontend/pubspec.yaml` gebruikt Riverpod. Er zijn
geen verder gespecificeerde, mechanisch-herstelbare ADR-conventies voor de
Flutter-code. Geen actie nodig.

## Geconstateerde afwijkingen die NIET veilig-mechanisch herstelbaar zijn

Onderstaande punten wijken af van de spec, maar herstel is géén puur mechanische,
gedrag-neutrale wijziging. Conform de story-randvoorwaarden (`Aannames`: "veilig
herstellen = puur mechanische, gedrag-neutrale aanpassingen") en de AC ("als er
geen veilige afwijkingen zijn, is een lege of minimale diff een geldige uitkomst")
zijn deze bewust NIET aangepast. Ze worden hier gedocumenteerd als input voor een
aparte refactor-story.

### A. Jackson-groupId is overal `com.fasterxml.jackson` i.p.v. `tools.jackson`
- **Conventie:** spec §1 + codeconventies — "Jackson 3.x groupId `tools.jackson`
  (niet `com.fasterxml.jackson`)".
- **Afwijkende code:** consistent in de hele backend — `pom.xml`
  (`com.fasterxml.jackson.module:jackson-module-kotlin`,
  `com.fasterxml.jackson.datatype:jackson-datatype-jsr310`) en alle imports
  (`com.fasterxml.jackson.databind.ObjectMapper`,
  `com.fasterxml.jackson.annotation.JsonProperty`). Er is nergens `tools.jackson`.
- **Waarom geen veilige fix:** de codebase compileert en alle tests slagen met
  `com.fasterxml.jackson`. Migreren naar `tools.jackson` is een
  dependency-/package-migratie (pom-wijziging + alle imports) met reëel risico op
  een brekende build; dat is niet "puur mechanisch & gedrag-neutraal". Bovendien
  blijft `jackson-annotations` (`com.fasterxml.jackson.annotation.JsonProperty`)
  óók in Jackson 3.x onder `com.fasterxml`, dus een blinde vervang-actie zou
  onjuist zijn. Dit is documentatie-/conventie-drift (vergelijk eerdere
  doc-drift-correcties zoals SF-327); aanbeveling: aparte, geteste migratiestory.

### B. Cross-module imports van interne (`domain`/`infrastructure`) klassen
- **Conventie:** spec §3 — modules mogen geen directe imports doen van interne
  klassen van andere modules; communicatie via publieke service-interface of
  `ApplicationEventPublisher`.
- **Afwijkende code (selectie):**
  - `common/StartupRunner.kt` → `auth.domain.User`, `auth.infrastructure.UserRepository`
  - `admin/domain/AdminServiceImpl.kt` → `auth.domain.User`, `auth.infrastructure.UserRepository`
  - `admin/domain/AdminCostsServiceImpl.kt` → `external_call.infrastructure.ExternalCallRepository`
  - `settings/api/SettingsController.kt` → `podcast_source.infrastructure.PodcastFeedFetcher`
  - `podcast/domain/PodcastTranslationServiceImpl.kt` + `PodcastTranslator.kt` → `podcast_source.infrastructure.PodcastEpisodeRepository`
  - `events/domain/*` → `request.infrastructure.TavilyClient/TavilyResult`, `podcast_source.infrastructure.WhisperClient/AudioTranscoder`
  - `podcast_source/domain/*` → `rss.domain.RssRefreshRequested/PodcastPromotionRequested`, `rss.infrastructure.RssItemRepository`
- **Waarom geen veilige fix:** herstel vereist het invoeren van publieke
  service-interfaces of het verplaatsen van (event-)klassen naar publieke
  module-roots. Klassen verplaatsen wijzigt hun fully-qualified name en raakt
  potentieel (de-)serialisatie en Spring-bean-resolutie; dit is een architecturale
  refactor, geen mechanische import-fix. Buiten veilige scope.

### C. Domeinmodellen worden direct als HTTP-response geserialiseerd
- **Conventie:** spec §4 — "Domeinmodellen worden nooit direct geserialiseerd naar
  HTTP-responses"; response-DTO's horen in `module/api/dto/`.
- **Afwijkende code:** domeinmodellen in de moduleroot dragen Jackson-annotaties
  en worden direct geretourneerd, bv. `rss/RssService.kt` (`data class RssItem`
  met `@...JsonProperty("isRead")`), `feed/FeedService.kt`, `request/RequestService.kt`,
  `settings/SettingsService.kt`.
- **Waarom geen veilige fix:** het introduceren van aparte response-DTO's +
  mapping is een functionele refactor met risico op gewijzigde JSON-output
  (veldnamen/volgorde) — dus mogelijk observeerbare gedragswijziging. Valt onder
  de error-/uitsluitingsrandvoorwaarde en is bewust niet doorgevoerd.

## Build & tests

`mvn test` in `newsfeedbackend/newsfeedbackend/` (met netwerk, deps gedownload):
**BUILD SUCCESS** — 19 tests groen (`AiPricingPropertiesTest` 4,
`RssFetcherImageUrlTest` 6, `PodcastScriptParserTest` 9), 0 failures/errors.
Geen bestaande tests aangepast.

> Flutter `analyze`/`test` kunnen niet lokaal draaien (geen flutter/dart-binary in
> de developer-runner); CI valideert de frontend-tests. Er zijn geen
> frontend-codewijzigingen in deze run.

## Conclusie / rationale

Er zijn **geen veilige, mechanische, gedrag-neutrale afwijkingen** om te herstellen.
De gecontroleerde mechanische conventies (geen OpenAPI-generator, `@Value`-targets,
controller-laagscheiding, comment-conventie) zijn al conform. De wél aanwezige
afwijkingen (Jackson-groupId, cross-module imports, directe domeinmodel-serialisatie)
vereisen een dependency-migratie of architecturale refactor met buildrisico c.q.
mogelijke gedragswijziging en vallen daarmee buiten "veilig herstellen". Conform de
acceptance criteria is dit een geldige **minimale diff** (alleen dit worklog),
met de bevindingen vastgelegd als input voor een aparte refactor-story.

## Review (reviewer, SF-373)

[info] Volledige story-diff t.o.v. `main` geverifieerd: alleen dit worklog
(`git diff main...HEAD --stat` = 1 file, +131). Geen code-/testwijzigingen,
dus gedrag-neutraal per definitie.

[info] Audit-claims steekproefsgewijs geverifieerd en accuraat:
- Geen `openapi-generator` in `pom.xml`; geen `Repository`-imports in
  `*Controller.kt`.
- De enige twee `@Value` zonder `@param:` zijn exact de gedocumenteerde
  uitzonderingen (`PodcastAsyncConfig.kt:13` @Bean-param,
  `PodcastTranscriptWorker.kt:47` plain param).
- Afwijkingen A/B/C bevestigd aanwezig (Jackson `com.fasterxml`, cross-module
  import `admin/domain/AdminServiceImpl.kt:5-6` → `auth.*`, `RssItem` met
  `@JsonProperty` in `rss/RssService.kt`). De rationale "niet veilig-mechanisch
  herstelbaar" is correct.

[info] Worklog eindigt schoon, geen `agent_tips_update`/`phase`-JSON-artefacten.

Akkoord: minimale diff is een geldige uitkomst conform de AC; niet-erroren is
correct want geen conventie dwingt een gedragswijziging af.
