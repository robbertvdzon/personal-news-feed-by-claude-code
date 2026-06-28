# SF-523 - Worklog

Story-context bij eerste pickup:
Gedrag-neutraal consistentiewerk doorvoeren

Spoor afwijkende patronen op in backend (newsfeedbackend/newsfeedbackend/) en Flutter-frontends (frontend/, frontend-reader/) en trek ze gelijk naar de norm: docs/factory/technical-spec.md §Codeconventies + specs/backend-technical-spec.md (logging-niveaus SLF4J INFO/DEBUG/WARN/ERROR, geen comments tenzij WHY, @param:Value-use-site-target op constructor-val, lagen API->domain->infrastructure, Spring Modulith-grenzen) en specs/openapi.yaml voor API; waar de spec zwijgt het aantoonbaar dominante codebase-patroon. STRIKT gedrag-neutraal: bij twijfel niet doorvoeren of in error gaan. Voer een review-stap uit binnen dit ontwikkelwerk. Buiten scope / melden in worklog i.p.v. fixen: Jackson-groupId com.fasterxml.jackson vs tools.jackson, cross-module domain/infrastructure-imports, domeinmodellen direct als HTTP-response; raak integratietests/e2e, build/CI-config en migraties niet aan. mvn test moet groen blijven zonder tests aan te passen om gedrag te maskeren; zou groen-krijgen een (integratie)test-wijziging vereisen, ga in error. Kleine of lege diff is een geldige uitkomst. Documenteer doorgevoerde wijzigingen en gemelde afwijkingen in docs/stories/worklog/SF-523-worklog.md.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## Uitkomst: lege code-diff (geldig conform AC)

De backend is bij verificatie volledig conform de veilig-herstelbare conventies; er is
geen gedrag-neutrale consistentieverbetering meer te maken. Dit bevestigt de uitkomst van
de vorige nightly consistentie-stories (SF-392, SF-456, SF-502/504): de codebase is al
geconsolideerd. Een lege diff is conform de acceptance criteria een geldige uitkomst.

### Geverifieerd conform (backend, newsfeedbackend/newsfeedbackend/)
Verificatie via grep over `src/main`:
- `@param:Value` use-site-target: 0 kale `@Value` op constructor-`val`/`var`.
  Twee documenteerde uitzonderingen blijven terecht ongewijzigd (geen `val`/`var`, dus
  `@param:` is daar fout/redundant):
  - `podcast_source/infrastructure/PodcastAsyncConfig.kt:13` — `@Value` op een
    `@Bean`-methodeparameter.
  - `podcast_source/domain/PodcastTranscriptWorker.kt:47` — `@Value` op een plain
    constructor-parameter zonder `val`/`var`.
- Logger-declaratie: 39/39 conform `private val log = LoggerFactory.getLogger(javaClass)`;
  geen afwijkende varianten.
- external_call-fallback: 10/10 conform `log.warn("[Tag] could not log external_call: {}", e.message)`.
- Geen inline `data class`-DTO's in `*Controller.kt` (request-DTO's staan in `module/api/dto/`, SF-437).
- Lagenstructuur API→domain→infrastructure: alle `@RestController` onder `*/api/`.
- `@RequestMapping` op klasse-niveau bij 12/13 controllers (zie melding hieronder).
- Mapping-stijl (`@GetMapping`/`@PostMapping`/etc.) consistent (65 mappings).

### Gemeld i.p.v. gefixt (buiten scope / niet veilig gedrag-neutraal)
- **SettingsController zonder klasse-`@RequestMapping`**: bedient bewust meerdere prefixes
  (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`). Een base-path toevoegen zou de
  URL's wijzigen → gedragsverandering. Niet gewijzigd.
- **Jackson-groupId**: code gebruikt overal `com.fasterxml.jackson`; spec is hier inmiddels
  aligned. Geen afwijking, geen actie.
- **Cross-module imports van interne domain/infrastructure-klassen**: bestaande architecturale
  afwijkingen, niet door een verify-test afgedwongen; refactor, geen mechanische gedrag-neutrale fix.
- **Domeinmodellen direct als HTTP-response** (`@JsonProperty` op domeinmodellen): bewust
  vastgelegde §4-afwijking; herstel is een gedragsgevoelige refactor. Niet gewijzigd.

### Frontends (frontend/, frontend-reader/)
- Geen `print(`-gebruik in beide apps; één `debugPrint` in `podcast_detail_screen.dart`
  (binnen-app niet afwijkend). Geen TODO/FIXME-restanten.
- `frontend/` en `frontend-reader/` zijn twee aparte apps met een eigen structuur
  (full app met `screens/`/`providers/`/`models/` vs. platte reader-app). Verschillen tussen
  de twee apps zijn cross-app, geen normafwijking *binnen* een app; niet gelijkgetrokken.
- Geen lokale flutter/dart-binary op de developer-runner; `flutter analyze`/`test` worden door
  CI gevalideerd. Er zijn geen frontend-codewijzigingen, dus geen nieuwe frontend-tests nodig.

### Tests
- `mvn test` (backend): **BUILD SUCCESS**, 25 tests groen (RssFetcherImageUrlTest 6,
  AiPricingPropertiesTest 4, ApiRequestDtoContractTest 6, PodcastScriptParserTest 9).
- Geen compiler-warnings (de `@param:Value`-migratie is compleet; geen "annotation applied to
  value parameter only"-waarschuwingen meer).
- Geen tests aangepast; geen integratie-/e2e-scenario's geraakt.

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Brede verificatie uitgevoerd; codebase blijkt al volledig geconsolideerd → bewust lege code-diff.
- Niet-veilige/architectuurgevoelige afwijkingen hierboven gemeld i.p.v. zelf hersteld.

## Tester-verificatie (SF-525)

Verificatie van de bovenstaande claims via grep/code-inspectie op `main...HEAD`:
- **Code-diff**: `git diff --name-only main...HEAD` toont uitsluitend dit worklog-bestand;
  geen `.kt`/Dart/test/infra-wijzigingen → gedrag is per constructie identiek aan `main`.
  Een lege code-diff is conform de AC een geldige uitkomst voor een consistentie-pass.
- **@Value**: 2 bare `@Value` = exact de gedocumenteerde uitzonderingen
  (`PodcastAsyncConfig.kt:13` @Bean-param, `PodcastTranscriptWorker.kt:47` plain param zonder
  `val`/`var`); 21× `@param:Value` elders; 0 kale `@Value` op constructor-`val`. ✓
- **Logger**: 39/39 `LoggerFactory.getLogger(javaClass)`, 0 afwijkende varianten. ✓
- **external_call WARN-fallback**: 10 conform. ✓
- **Controllers**: 13/13 `@RestController` onder `*/api/`; klasse-`@RequestMapping` op 12/13;
  enige `NONE` = `SettingsController` (bedient meerdere prefixes `/api/settings`, `/api/rss-feeds`,
  `/api/podcast-feeds`, `/api/settings/event-*` → base-path toevoegen wijzigt URL's → terecht
  niet gewijzigd). ✓
- **Geen inline `data class` in `*Controller.kt`** (0). ✓
- **Frontends**: 0 `print(`; 1 `debugPrint` in `podcast_detail_screen.dart`. ✓
- **Browser-/preview-test**: niet uitgevoerd — er zijn geen frontend- of backend-codewijzigingen,
  dus geen gedrag om visueel te verifiëren.
- **`mvn test`**: niet opnieuw gedraaid — bij een lege code-diff is het resultaat identiek aan
  `main`, en de volledige Cucumber-suite vereist de gedeelde prod-DB (destructief risico). De
  developer rapporteerde BUILD SUCCESS (25 unit-tests groen) zonder testwijzigingen.

Conclusie: alle worklog-claims kloppen; geen afwijkingen gevonden. **tested-ok**.
