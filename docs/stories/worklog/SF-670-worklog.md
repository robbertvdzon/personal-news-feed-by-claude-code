# SF-670 - Worklog

Story-context bij eerste pickup:
ADR-naleving herstellen (gedrag-neutraal)

Vergelijk de backend-Kotlincode (newsfeedbackend/newsfeedbackend/) en frontends (frontend/, frontend-reader/) tegen de ADR-equivalenten specs/backend-technical-spec.md en docs/factory/technical-spec.md. Herstel uitsluitend veilige, gedrag-neutrale afwijkingen (bv. comment-conventies, @param:-target bij @Value, opschoning zonder runtime-effect); functioneel gedrag blijft exact ongewijzigd. Documenteer niet-veilig herstelbare afwijkingen (o.a. Jackson com.fasterxml vs tools.jackson, cross-module domain/infrastructure-imports, domeinmodel-als-HTTP-response) in het worklog i.p.v. ze door te voeren; vergt een herstel een gedragswijziging, ga dan in error met vermelding van ADR-conventie en afwijking. Een lege of kleine diff (vaak alleen het worklog) is een geldige uitkomst. Raak e2e/scenarios/ en integratietests niet aan. Werk docs/stories/worklog/SF-670-worklog.md bij. Voer een eigen review-stap uit voordat je afrondt.

## SF-672 — developing

Stappenplan:
- [x] read issue and target docs (.task.md, docs/factory, specs/backend-technical-spec.md)
- [x] vergelijk backend-Kotlincode + frontends tegen ADR-conventies
- [x] herstel veilige, gedrag-neutrale afwijkingen (geen gevonden)
- [x] documenteer niet-veilig herstelbare afwijkingen
- [x] run relevant tests (mvn test)
- [x] eigen review-stap + update story-log

### Uitkomst: worklog-only (geen code-diff)

De backend is sinds SF-588 (story SF-586) niet meer gewijzigd in `newsfeedbackend/newsfeedbackend/src`
(geverifieerd met `git diff --stat c47b5a9 HEAD`). De codebase was bij SF-586 al volledig conform de
veilig-herstelbare conventies; deze run bevestigt dat de convergentie ongewijzigd is. Geen gedrag-neutrale
afwijking gevonden die hersteld moet worden → geen code-wijziging.

### Geverifieerde naleving (grep tegen src/main)
- **`@param:Value`**: 0 kale `@Value` op een `val/var` primary-constructor property. De 2 resterende kale
  `@Value` zijn de gedocumenteerde uitzonderingen die `@param:` NIET mogen krijgen:
  - `podcast_source/infrastructure/PodcastAsyncConfig.kt` — `@Value` op een `@Bean`-methodeparameter
    (`@param:` = compilefout).
  - `podcast_source/domain/PodcastTranscriptWorker.kt` — `@Value` op een plain constructor-param zonder
    `val/var` (`@param:` = redundant-target-warning).
- **Logger**: 39/39 `private val log = LoggerFactory.getLogger(javaClass)` — geen afwijkende vorm.
- **external_call-fallback**: alle `could not log external_call`-handlers gebruiken `log.warn(...)`.
- **DTO-plaatsing**: geen inline `data class` in `*Controller.kt` (DTO's leven in `module/api/dto/`, sinds SF-437).
- **KDoc**: geen KDoc-blok ná de Spring-stereotype-annotatie (conventie: KDoc vóór `@Service/@Component`).
- **OpenAPI-generator**: niet aanwezig in `pom.xml`; controllers blijven handgeschreven `@RestController`.
- **Jackson-spec**: `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md` noemen sinds SF-502
  `com.fasterxml.jackson` (matcht pom.xml + imports). Geen `tools.jackson`-referenties meer → geen drift.

### Frontends
Geen wijzigingen in `frontend/` of `frontend-reader/` sinds SF-586 (`git diff --stat c47b5a9 HEAD`). De
enige bekende cross-app-afwijking (`frontend-reader/lib/main.dart` `_Tab` vs `frontend/` `_FeedTab/_RssTab`)
is cross-app, geen norm binnen de reader-app → geen actie.

### Vaste, vooraf-erkende niet-veilig-herstelbare afwijkingen (gemeld, NIET gewijzigd, GEEN error-exit)
Deze zijn in `.task.md` (Aannames) expliciet als bekend/structureel benoemd en vergen een gedrag-/risico-wijziging:
1. **Jackson groupId** `com.fasterxml.jackson` waar oudere spec-tekst `tools.jackson` noemde. De spec is
   inmiddels op `com.fasterxml` aligned; migreren naar Jackson 3 is een risicovolle dependency-/package-change,
   geen mechanische fix (jackson-annotations blijft ook in Jackson 3 onder `com.fasterxml.jackson.annotation`).
2. **Cross-module imports** van interne `domain`/`infrastructure`-klassen tussen modules (o.a.
   `common/admin` → `auth.infrastructure.UserRepository`; `events.domain` → `request.infrastructure.TavilyClient`
   + `podcast_source.infrastructure.WhisperClient`; `podcast_source.domain` → `rss.*`;
   `settings.api` → `podcast_source.infrastructure.PodcastFeedFetcher`). Een `ApplicationModules.verify()`-test
   toevoegen zou de build laten falen; dit zijn architecturale refactors.
3. **Domeinmodellen als HTTP-response**: controllers retourneren domeinmodellen (bv. `RssItem` met
   `@JsonProperty`) direct als response i.p.v. response-DTO's. Herstel vergt nieuwe DTO's + mapping = refactor.
4. **`settings/api/SettingsController.kt`** heeft geen class-niveau `@RequestMapping` omdat het meerdere
   prefixes bedient (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`); een base-path toevoegen zou de
   URLs wijzigen = gedragswijziging.

### Tests
`mvn test` (newsfeedbackend/newsfeedbackend) → **BUILD SUCCESS, 28 tests groen** (0 failures, 0 errors):
RssFetcherImageUrlTest (6), AiPricingPropertiesTest (4), ApiRequestDtoContractTest (6),
PodcastScriptParserTest (9), VideoAudioDownloaderArgsTest (3).
`PNF_DATABASE_URL` was niet gezet, maar de aanwezige unit-tests raken de DB niet, dus dit blokkeert niet.
E2e-suite (`e2e/scenarios/`) en integratietests zijn niet aangeraakt.

### Eigen review
Diff bevat uitsluitend dit worklog; geen `src`-/spec-/frontend-wijzigingen. Geen rauwe JSON-artefacten in
story-bestanden. Functioneel gedrag exact ongewijzigd.

Done / rationale:
- Backend + frontends geverifieerd conform de veilig-herstelbare ADR-conventies; codebase al geconvergeerd
  sinds SF-586 → geen gedrag-neutrale fix nodig.
- Niet-veilig-herstelbare afwijkingen hierboven gemeld i.p.v. doorgevoerd, conform de vooraf-erkende lijst
  in `.task.md`.
