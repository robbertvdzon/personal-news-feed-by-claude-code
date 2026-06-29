# SF-698 - Worklog

Story-context bij eerste pickup:
Afwijkende patronen gladstrijken (gedrag-neutraal)

Scan backend (newsfeedbackend/newsfeedbackend/) en beide frontends (frontend/, frontend-reader/) op afwijkende patronen t.o.v. de norm: naamgeving, code-/pakketstructuur, error-handling, logging(-niveaus), API-conventies en plekken waar hetzelfde probleem op meerdere manieren is opgelost. Norm = docs/factory/technical-spec.md §Codeconventies + specs/backend-technical-spec.md + specs/openapi.yaml; het meest voorkomende patroon is leidend, uitschieters worden daarnaartoe gelijkgetrokken. Voer uitsluitend veilige, gedrag-neutrale wijzigingen door; bij twijfel of gedrag verandert: niet doorvoeren of in error gaan. Pas integratietests en e2e/scenarios/ NIET aan (vangnet) - vereist groen krijgen zo'n wijziging dan in error. Meld bekende niet-veilige afwijkingen (Jackson com.fasterxml vs tools.jackson, cross-module domain/infra-imports, domeinmodel-als-HTTP-response) in de worklog i.p.v. ze te fixen. Voer een zelfreview uit op de diff. Een kleine of lege diff is een geldige uitkomst.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## Bevinding: codebase al geconvergeerd -> worklog-only diff

Dit is een nightly consistentie-pass (familie SF-608/SF-586/SF-670). De backend- en
frontend-bronnen zijn sinds SF-579 niet meer gewijzigd:

    git diff --stat de75274 HEAD -- newsfeedbackend/newsfeedbackend/src frontend/lib frontend-reader/lib
    (lege output)

Alle veilig-herstelbare conventies zijn nogmaals geverifieerd en blijven conform; er
is geen gedrag-neutrale wijziging die nog open staat. Een lege code-diff is hier de
correcte, geldige uitkomst.

### Geverifieerd conform (grep over newsfeedbackend/.../src/main/kotlin)
- `@Value` op een primary-constructor-property: overal `@param:Value` -- geen kale `@Value`
  op `val`/`var`. De twee resterende kale `@Value` zijn de gedocumenteerde uitzonderingen
  die juist NIET met `@param:` mogen (anders compilefout/redundante-target-warning):
  - `podcast_source/infrastructure/PodcastAsyncConfig.kt` -- `@Value` op een `@Bean`-methodeparameter.
  - `podcast_source/domain/PodcastTranscriptWorker.kt` -- `@Value` op een plain constructor-param zonder `val`/`var`.
- Logger: 39/39 `private val log = LoggerFactory.getLogger(javaClass)` (0 afwijkend).
- external_call-fallback: 10/10 `log.warn("... could not log external_call: {}", e.message)` (0 afwijkend).
- Geen inline `data class`-DTO in `*Controller.kt` (DTO's staan in `module/api/dto/`, sinds SF-437).
- KDoc voor de Spring-stereotype-annotatie; geen openapi-generator-plugin; Jackson-spec
  staat op `com.fasterxml.jackson` (geen drift meer sinds SF-502).
- Frontends: geen kale `print(`-logging in `frontend/lib` of `frontend-reader/lib`.
  `frontend/` (gestructureerde app) vs `frontend-reader/` (platte reader-app) is een
  bewust cross-app-verschil, geen norm-afwijking binnen een app.

### Tests
- `mvn test` (backend): **BUILD SUCCESS**, 28 tests groen
  (RssFetcherImageUrlTest 6, AiPricingPropertiesTest 4, ApiRequestDtoContractTest 6,
  PodcastScriptParserTest 9, VideoAudioDownloaderArgsTest 3). `PNF_DATABASE_URL` niet
  nodig: de unit-tests raken de DB niet.
- Geen enkele integratietest of `e2e/scenarios/` gewijzigd.

### Bekende niet-veilige afwijkingen (gemeld, NIET gewijzigd)
Corrigeren raakt gedrag/serialisatie/architectuur -> conform Aannames gemeld i.p.v. hersteld:
1. Jackson-groupId `com.fasterxml.jackson` i.p.v. spec-historie `tools.jackson` -- sinds
   SF-502 is de spec zelf op `com.fasterxml` aligned, dus feitelijk geen afwijking meer;
   migreren blijft een risicovolle dependency-/package-change.
2. Cross-module imports tussen interne `domain`/`infrastructure`-klassen (o.a.
   common/admin -> auth.infrastructure.UserRepository, events.domain -> request/podcast_source
   infrastructure) -- architecturale refactor, niet mechanisch gedrag-neutraal.
3. Domeinmodellen (RssItem e.d.) met `@JsonProperty` die direct als HTTP-response worden
   geserialiseerd -- wijzigen raakt het API-contract/serialisatie.
4. `settings/api/SettingsController.kt` zonder klasse-`@RequestMapping` (bedient 3 prefixes:
   /api/settings, /api/rss-feeds, /api/podcast-feeds) -- base-path toevoegen wijzigt URLs.

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Verificatie uitgevoerd: codebase is al consistent; geen gedrag-neutrale wijziging open.
- Resultaat = worklog-only (lege code-diff), conform de geldige-uitkomst-regel in de AC.
