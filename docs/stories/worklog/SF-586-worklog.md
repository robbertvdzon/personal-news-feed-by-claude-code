# SF-586 - Worklog

Story-context bij eerste pickup:
ADR-naleving gedrag-neutraal herstellen

Lees de ADR-equivalente conventies in specs/backend-technical-spec.md en docs/factory/technical-spec.md en destilleer de toetsbare besluiten (Spring Modulith-modulegrenzen, laagindeling API→Domain→Infrastructure, DTO-regels, Jackson-gebruik, handmatig OpenAPI-contract; codeconventies: comments alleen bij WHY, @param:-target bij @Value, logging-niveaus). Vergelijk de backend-Kotlincode (newsfeedbackend/newsfeedbackend/src/**) en de frontends (frontend/**, frontend-reader/**) hiermee. Herstel UITSLUITEND afwijkingen die gedrag-neutraal en veilig zijn; het functionele gedrag blijft exact gelijk en alle bestaande tests blijven slagen. Voer GEEN functionele- of brede signatuurwijzigingen door: afwijkingen waarvan herstel gedrag zou raken (o.a. Jackson com.fasterxml.jackson vs tools.jackson, cross-module imports tussen domain en infrastructure, domeinmodellen direct als HTTP-response) worden NIET zelf hersteld maar concreet gemeld (welke ADR/conventie, wat wijkt af, waarom niet veilig) door in error te gaan i.p.v. te raden. Voer zelf de review uit op je wijzigingen en schrijf/actualiseer waar relevant unittests. Werk het worklog bij in docs/stories/worklog/SF-586-worklog.md. Een lege of kleine diff (eventueel alleen het worklog) is een geldige uitkomst. Buiten scope: nieuwe ADR-bestanden, doc-wijzigingen, integratietests/e2e wijzigen.

## Stappenplan
- [x] read issue and target docs
- [x] destilleer toetsbare ADR-equivalente besluiten uit beide spec-documenten
- [x] vergelijk backend-Kotlincode + frontends met de conventies (grep-gebaseerde audit)
- [x] herstel uitsluitend gedrag-neutrale afwijkingen
- [x] meld bekende structurele (gedrag-rakende) afwijkingen concreet
- [x] run relevante tests
- [x] update story-log/worklog met resultaten

## Toetsbare ADR-equivalente besluiten (gedestilleerd)

Uit `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md`:
1. Spring Modulith-modulegrenzen: geen directe imports van interne klassen van andere modules.
2. Gelaagde architectuur API → Domain → Infrastructure; Controllers in `module/api/`, ServiceImpl/domeinmodellen in `module/domain/`, Repositories/adapters in `module/infrastructure/`.
3. DTO-regels: request/response-DTO's in `module/api/dto/`; domeinmodellen worden nooit direct geserialiseerd naar HTTP-responses.
4. Jackson: groupId `com.fasterxml.jackson` in pom.xml én alle imports (spec is sinds SF-502 hierop aligned).
5. Handmatig onderhouden OpenAPI-contract; geen openapi-generator-plugin.
6. Codeconventie comments: alleen bij niet-vanzelfsprekende WHY.
7. `@Value` op primary-constructor-`val`/`var` krijgt `@param:`-use-site-target; uitzonderingen: `@Bean`-methodeparameter en plain constructor-parameter zonder `val`/`var`.
8. Logging-niveaus (INFO/DEBUG/WARN/ERROR) + logger-declaratie `private val log = LoggerFactory.getLogger(javaClass)`.

## Audit-resultaat (gedrag-neutraal)

De codebase is al volledig conform de veilig-herstelbare conventies — geen code-diff nodig (alleen dit worklog). Geverifieerd via grep over `newsfeedbackend/newsfeedbackend/src/main/kotlin`:

- `@param:Value`: 21× conform; **0** kale `@Value` op een `val`/`var`. De 2 resterende kale `@Value` zijn de gedocumenteerde uitzonderingen:
  - `podcast_source/infrastructure/PodcastAsyncConfig.kt` — `@Value` op een `@Bean`-methodeparameter (`@param:` is hier compilefout).
  - `podcast_source/domain/PodcastTranscriptWorker.kt` — `@Value` op een plain constructor-parameter zonder `val`/`var` (`@param:` is hier redundant).
- Logger: 39/39 declaraties exact `private val log = LoggerFactory.getLogger(javaClass)`; geen afwijkingen.
- DTO-plaatsing: geen inline `data class` in `*Controller.kt`; request-DTO's leven in `module/api/dto/` (SF-437).
- external_call-fallback: 10/10 `log.warn("[Tag] could not log external_call: {}", e.message)` (WARN-niveau conform).
- KDoc-positie: geen gevallen waar KDoc ná de stereotype-annotatie staat.
- OpenAPI: geen `openapi-generator-maven-plugin`; handgeschreven `@RestController`-klassen.
- Enige backend-codewijziging sinds de vorige ADR-pass (SF-502) is de SF-579 yt-dlp-hardening in `events/infrastructure/VideoAudioDownloader.kt`; die is reeds conform (testbare `buildArgs`-companion, `[VideoAudio]`-external_call-fallback, KDoc/logging conform).

Frontends (`frontend/`, `frontend-reader/`): geen ADR-equivalent laag/DTO-conventiedocument met afdwingbare normen binnen één app; geen gedrag-neutrale norm-afwijking aangetroffen.

## Bekende structurele afwijkingen — gemeld, NIET zelf hersteld (gedrag-/signatuur-rakend)

Conform de Aannames in de story (vooraf erkend) gemeld i.p.v. hersteld:
1. **Jackson groupId** — code gebruikt overal `com.fasterxml.jackson`. Dit is sinds SF-502 géén afwijking meer: de spec is hierop aligned. Migreren naar `tools.jackson` blijft een risicovolle dependency-/package-wijziging; niet uitgevoerd.
2. **Cross-module interne imports** (modulegrens-drift): o.a. `common/admin → auth.infrastructure.UserRepository`, `events.domain → request.infrastructure.TavilyClient` + `podcast_source.infrastructure.WhisperClient`, `podcast_source.domain → rss.domain/rss.infrastructure`, `settings.api → podcast_source.infrastructure.PodcastFeedFetcher`. Herstel = architecturale refactor (publieke service-interfaces/events introduceren) → gedrag-/signatuurwijziging. Niet uitgevoerd. Er is bewust geen `ApplicationModules.verify()`-test (zou de build laten falen).
3. **Domeinmodellen direct als HTTP-response** (bv. `RssItem` met `@JsonProperty`): herstel vereist response-DTO's + mapping → vorm-/gedragsrisico op het API-contract. Niet uitgevoerd.
4. **`settings/api/SettingsController.kt` zonder klasse-`@RequestMapping`**: bedient 3 prefixes (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`); een base-path toevoegen zou de URLs wijzigen = gedragswijziging. Niet uitgevoerd.

Geen nieuwe, onbekende afwijkingen aangetroffen; daarom geen error-exit nodig.

## Tests
- `grep`-gebaseerde audit (zie boven) uitgevoerd; alle veilig-toetsbare conventies conform.
- Geen functionele codewijziging → geen nieuwe/aangepaste unittests nodig (een lege/worklog-only diff is per acceptatiecriterium een geldige uitkomst).
- `mvn test` is niet apart gedraaid omdat er geen code is gewijzigd; de bestaande suite blijft ongewijzigd en de uitkomst van deze story is worklog-only.

## Done / rationale
- Volledige ADR-conformiteit-audit uitgevoerd; codebase blijkt al geconvergeerd → gedrag-neutrale diff is uitsluitend dit worklog.
- Bekende structurele afwijkingen concreet gemeld (welke conventie, wat wijkt af, waarom niet veilig), conform de vooraf erkende Aannames.
