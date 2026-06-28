# SF-502 - Worklog

Story-context bij eerste pickup:
ADR-naleving: veilig herstel + rapportage

Vergelijk de code (backend newsfeedbackend/ en beide Flutter-frontends) met de conventies in specs/backend-technical-spec.md en docs/factory/technical-spec.md (de ADR-equivalenten; er is geen docs/adr/-map). Herstel uitsluitend puur structurele/cosmetische, gedrag-neutrale afwijkingen (bv. misplaatste/uitleggende comments, lokaal oplosbare conventie-overtredingen, @Value @param:-target). Voer GEEN functionele, API-, data- of UI-gedragswijzigingen door. Afwijkingen waarvan herstel een functionele/contractwijziging zou vereisen (o.a. Jackson com.fasterxml.jackson vs tools.jackson, cross-module domain/infrastructure-imports, domeinmodel direct als HTTP-response) niet wijzigen maar concreet rapporteren (conventie, bestand, wat wijkt af, waarom onveilig); bij echte onduidelijkheid in error gaan i.p.v. wachten. Bestaande tests niet aanpassen. Voer zelf een review-stap uit op gedrag-neutraliteit. Een lege/zeer kleine diff (eventueel alleen worklog) is een geldige uitkomst.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes (geen code-wijziging nodig — code is al conform)
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

---

## SF-504 — ADR-naleving: veilig herstel + rapportage

ADR-equivalente conventiebronnen (er is geen `docs/adr/`-map): `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md`.

### Checklist en bevindingen

| # | Conventie (bron) | Status | Actie |
|---|------------------|--------|-------|
| 1 | Geen OpenAPI Generator-plugin in `pom.xml`; controllers handgeschreven (backend-spec §2, §8) | ✅ Conform | Geen |
| 2 | Jackson-groupId `com.fasterxml.jackson` in pom + imports (backend-spec §1, technical-spec §Codeconventies) | ✅ Conform (spec al in lijn) | Geen |
| 3 | `@Value` op primary-constructor-`val` heeft `@param:`-target (technical-spec §Codeconventies) | ✅ Conform | Geen |
| 4 | DTO's in `module/api/dto/` (backend-spec §4) | ✅ Conform (hersteld in SF-437) | Geen |
| 5 | Logger-patroon `private val log = LoggerFactory.getLogger(javaClass)` (backend-spec §5) | ✅ Conform (39/39) | Geen |
| 6 | Uniforme external_call-fallback-logregel (consistentie) | ✅ Conform (10/10) | Geen |
| 7 | Controllers zonder business logic, geen directe repository-aanroep (backend-spec §4) | ✅ Conform | Geen |
| 8 | KDoc-positie vóór Spring-stereotype-annotatie / "geen comments tenzij WHY" (technical-spec §Codeconventies) | ✅ Conform | Geen |
| 9 | Modulegrenzen: geen cross-module imports van interne klassen (backend-spec §3) | ❌ Afwijking (niet veilig) | Gedocumenteerd, gedefereerd |
| 10 | Domeinmodellen nooit direct geserialiseerd als HTTP-response (backend-spec §4) | ❌ Afwijking (niet veilig) | Gedocumenteerd, gedefereerd |
| 11 | `SettingsController` zonder class-level `@RequestMapping` base-path | ⚠️ Bewuste afwijking (niet veilig) | Gedocumenteerd, gedefereerd |
| 12 | Flutter-frontends: backend-conventies (modulith/lagen/DTO/Jackson/@Value) n.v.t. | ✅ n.v.t. | Geen |

#### Detail conform-punten (geverifieerd, geen wijziging)
- **OpenAPI (1):** geen `openapi-generator-maven-plugin` in `pom.xml`, geen `target/generated-sources/openapi/`. Controllers zijn handgeschreven `@RestController`-klassen.
- **Jackson (2):** `pom.xml` en alle imports gebruiken `com.fasterxml.jackson`. De spec is op dit punt in lijn met de code (eerdere doc-drift `tools.jackson` is in de spec rechtgezet). Migreren naar `tools.jackson` zou een risicovolle dependency-/package-wijziging zijn — niet veilig, niet uitgevoerd.
- **`@Value` (3):** alle `@Value` op primary-constructor-`val` gebruiken `@param:Value(...)` (geverifieerd: 0 kale `@Value` op een `val`). De twee kale `@Value` zijn exact de gedocumenteerde uitzonderingen: `PodcastAsyncConfig` (`@Bean`-methodeparameter; `@param:` is compilefout) en `PodcastTranscriptWorker` (plain constructor-param zónder `val`/`var`; `@param:` is redundant). `mvn test` levert geen Kotlin-warning over annotation-target. Conform.
- **DTO-plaatsing (4):** geen `data class`-DTO inline in een `*Controller.kt`; alle request-DTO's staan in `module/api/dto/` (verplaatst in SF-437). Conform.
- **Logger (5):** alle 39 loggers gebruiken exact `private val log = LoggerFactory.getLogger(javaClass)`; geen afwijkende varianten.
- **external_call-fallback (6):** alle 10 fallbacks gebruiken `log.warn("[<Tag>] could not log external_call: {}", e.message)`; uniform.
- **Comments/KDoc (8):** KDoc-blokken staan vóór de Spring-stereotype-annotatie (bv. `EventVideoRepository.kt`); geen inline comments aangetroffen die de "geen comments tenzij WHY"-regel schenden.

### Gedocumenteerd, niet hersteld (gedrag-/contract-rakend → aparte refactor-story)

Conform de scope-aanname dat bekende, niet-mechanische afwijkingen wél worden gemeld maar niet zelf hersteld (herstel zou gedrag/contract wijzigen).

**(9) Cross-module imports van interne klassen** — backend-spec §3 (modules importeren geen interne klassen van andere modules). Bestaande overtredingen o.a.:
- `common`/`admin` → `auth.infrastructure.UserRepository`
- `events.domain` → `request.infrastructure.TavilyClient` + `podcast_source.infrastructure.WhisperClient`
- `podcast_source.domain` → `rss.domain`-events + `rss.infrastructure.RssItemRepository`
- `settings.api` → `podcast_source.infrastructure.PodcastFeedFetcher`

Waarom niet veilig: er is geen `ApplicationModules.verify()`-test die dit afdwingt; herstel vereist het introduceren van publieke service-interfaces en herbedrading van afhankelijkheden — een architecturale refactor met gedragsrisico, niet mechanisch.

**(10) Domeinmodellen direct als HTTP-response** — backend-spec §4 (domeinmodellen worden nooit direct geserialiseerd). Controllers retourneren op meerdere plekken domeinmodellen (bv. `RssItem` met `@JsonProperty`) direct als response i.p.v. via een `*Response`-DTO. Waarom niet veilig: het invoeren van response-DTO's wijzigt de mapping-laag en kan de JSON-vorm (en daarmee het frontend-contract / `openapi.yaml`) raken — geen gedrag-neutrale mechanische fix.

**(11) `SettingsController` zonder class-level `@RequestMapping`** — de controller bedient drie verschillende prefixes (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`) en zet daarom het pad per methode. Een class-level base-path toevoegen zou de URL's wijzigen → contractwijziging. Bewuste afwijking; niet hersteld.

### Tests
- `mvn test` in `newsfeedbackend/newsfeedbackend/`: **BUILD SUCCESS**, `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0`. Geen testbestanden aangepast.
- Geen Kotlin annotation-target-warnings tijdens compile (bevestigt dat de `@param:Value`-conventie volledig is doorgevoerd).
- Flutter-frontends: er is lokaal geen flutter/dart-binary in de developer-runner; `flutter analyze`/`test` worden door CI gevalideerd. De backend-specifieke conventies (modulith, lagen, DTO, Jackson, `@Value`) zijn niet van toepassing op de Dart-code, dus geen frontend-codewijziging nodig.

### Zelf-review op gedrag-neutraliteit
Er zijn **geen** code-, API-, data- of UI-wijzigingen doorgevoerd. De enige diff bestaat uit dit worklog en de story-log. Het functionele gedrag is per definitie ongewijzigd. De code bleek bij controle reeds volledig in lijn met de veilig-herstelbare conventies (resultaat van eerdere passes SF-392/SF-435/SF-437/SF-456); de resterende afwijkingen zijn architecturaal/contract-rakend en zijn bewust gerapporteerd i.p.v. gewijzigd.

---

## SF-505 — Story-brede test (tester)

Docs-only diff (`git diff --name-only main...HEAD` = alleen story-log + dit worklog; geen `.kt`/Dart/test/infra). Gedrag is daarmee per definitie ongewijzigd; geen API/UI-effect, preview pnf-pr-150 ongewijzigd. Geen browser-screenshots nodig (geen frontend-codewijziging). `mvn test` niet opnieuw gedraaid: zonder codewijziging geen meerwaarde en de volledige Cucumber-suite raakt een gedeelde DB.

Worklog-claims geverifieerd via code-inspectie (greps in `newsfeedbackend/newsfeedbackend/src/main`):
- (1) geen `openapi-generator`-plugin in `pom.xml` — bevestigd.
- (2) Jackson = `com.fasterxml.jackson` (36 imports), 0× `tools.jackson` — bevestigd.
- (3) precies 2 kale `@Value`, exact de gedocumenteerde uitzonderingen (`PodcastAsyncConfig` @Bean-param `concurrency: Int`, `PodcastTranscriptWorker` plain-param `promotionTimeoutHours: Long`, beide géén `val`); 21× `@param:Value` — bevestigd.
- (4) geen inline `data class` in `*Controller.kt`; 5 DTO-bestanden onder `*/api/dto/` — bevestigd.
- (5) logger-patroon 39/39, geen afwijkende `LoggerFactory.getLogger`-variant — bevestigd.
- (6) external_call-fallback 10/10 — bevestigd.
- (11) `SettingsController` bedient 3 prefixes (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`) → class-level `@RequestMapping` zou URL's wijzigen; terecht gedefereerd — bevestigd.

Niet-veilig-herstelbare afwijkingen (9/10/11) zijn correct gerapporteerd i.p.v. gewijzigd. Resultaat: **tested** — alle controleerbare claims kloppen, geen gedragswijziging.
