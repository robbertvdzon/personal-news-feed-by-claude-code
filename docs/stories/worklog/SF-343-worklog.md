# SF-343 - Worklog

Story-context bij eerste pickup:
Code-kwaliteit & build-warnings refactoren (backend + beide frontends)

Puur gedragsneutraal kwaliteits-/refactorwerk. Backend (newsfeedbackend/newsfeedbackend/): SOLID/leesbaarheid binnen Spring Modulith-grenzen; los Maven build-warnings/deprecations op met bestaande, gelijkwaardige API's (geen dependency-/versie-bumps). Frontends (frontend/, frontend-reader/): Dart-analyzer-warnings en Android-Gradle-deprecations. Geen wijziging aan API-contract, DB-schema of Flyway. Tests onveranderd groen houden; integratietests niet aanraken. Bij twijfel of gedrag wijzigt: niet doorvoeren.

## Stappenplan

- [x] read issue and target docs
- [x] baseline van build-warnings vastleggen
- [x] backend warnings/deprecations wegwerken (gedragsneutraal)
- [x] backend tests draaien (mvn test)
- [x] update story-log met resultaten

## Done / rationale

### Backend (SF-344, deze subtaak)

**Baseline.** `mvn -DskipTests clean compile` op `newsfeedbackend/newsfeedbackend/` gaf 21 identieke Kotlin-compilerwaarschuwingen:

> `This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.`

Alle 21 betroffen een `@Value(...)`-annotatie op een primary-constructor *property* (val) verspreid over 8 klassen (AdminServiceImpl, OpenAiChatHttpClient, JwtService, VideoAudioDownloader, SharedFeedController, TtsClient, WhisperClient, TavilyClient). Dit is de bekende Kotlin 2.x-waarschuwing over de toekomstige wijziging van het standaard annotation-use-site-target.

**Fix (gedragsneutraal).** Voor elk van deze gevallen een expliciet `@param:`-use-site-target toegevoegd (`@Value(...)` -> `@param:Value(...)`). Spring leest de property nu net als voorheen uit de constructor-parameter, dus runtime-gedrag en injectie zijn exact identiek; alleen de toekomstige-default-ambiguiteit is expliciet vastgelegd, waardoor de waarschuwing verdwijnt. Geen dependency- of versie-bumps, geen API-/contract-/DB-wijziging.

**Bewust niet aangepast (zou nieuwe warning of compilefout geven):**
- `PodcastAsyncConfig.kt` — `@Value` staat op een `@Bean`-*methodeparameter*, geen constructor-property; `@param:` is daar ongeldig (compilefout) en de oorspronkelijke vorm gaf geen waarschuwing. Ongewijzigd gelaten.
- `PodcastTranscriptWorker.kt` — `@Value` staat op een *plain* constructor-parameter zonder `val`/`var`; daar is `param` het enige geldige target en `@param:` levert juist een nieuwe "Redundant annotation target 'param'"-waarschuwing op. Ongewijzigd gelaten.

**Verificatie.** `mvn test` (volledige build incl. Kotlin-compile van main + test):
- 0 waarschuwingen / 0 deprecations in de build-output (baseline 21 -> 0), geen nieuwe warnings geintroduceerd.
- Tests: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
- Bestaande unit-tests (RssFetcherImageUrlTest, AiPricingPropertiesTest, PodcastScriptParserTest) onveranderd en groen. `ModuleStructureTest` is niet aanwezig in de repo en er zijn geen module-grenzen gewijzigd. Integratietests niet aangeraakt (zijn niet aanwezig).

### Frontends (frontend/, frontend-reader/) — niet doorgevoerd in deze run

De factory developer-runner bevat geen flutter/dart-binary en geen Android-Gradle-toolchain (Android SDK). Daardoor kan voor de frontends geen warning-baseline worden vastgelegd en kan niet worden aangetoond dat eventuele Dart-analyzer-/Gradle-deprecatie-fixes de warnings daadwerkelijk laten verdwijnen *zonder nieuwe te introduceren* — een harde acceptance-eis. Conform "bij twijfel of een wijziging gedrag/warnings verandert: niet doorvoeren" en de pragmatische scope-clausule ("een coherente, veilige set die binnen een run volledig groen op te leveren is; volledigheid is geen vereiste") is het frontend-spoor bewust buiten deze run gehouden. De opgeleverde backend-set is op zichzelf coherent, gedragsneutraal en volledig groen geverifieerd.

## Review (SF-344)

Akkoord. Volledige story-diff `main...HEAD` beoordeeld.

- [info] 21 gedragsneutrale `@Value` -> `@param:Value` wijzigingen over 8 klassen, exact conform handover. `@param:` injecteert nog steeds via de constructor-parameter; runtime-gedrag identiek.
- [info] De twee uitzonderingen (`PodcastAsyncConfig` `@Bean`-methodeparam, `PodcastTranscriptWorker` plain param zonder `val`) staan correct ongewijzigd — `@param:` zou daar een compilefout resp. nieuwe warning geven.
- [info] Geen wijziging aan API-contract (`specs/openapi.yaml`), controllers, DB-schema, Flyway of Spring Modulith-modulegrenzen. Geen duplicate stereotype-annotaties. Geen rauwe JSON-artefacten in de worklog.
- [info] Frontend-spoor bewust uitgesteld wegens ontbrekende flutter/Gradle-toolchain; toegestaan onder de pragmatische scope-clausule. Aandachtspunt voor story-afronding: de frontend-warnings uit de story-scope blijven hiermee open.
