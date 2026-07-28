# SF-1401 - Worklog

Story-context bij eerste pickup:
@param:Value-conventie herstellen in de drie SSRF-escape-hatch-bestanden

Wijzig in de drie genoemde bestanden de `@Value(...)`-annotatie op de `ssrfAllowLoopback`-constructorparameter naar `@param:Value(...)`, conform docs/factory/technical-spec.md:78 en het patroon in JwtService.kt: SettingsServiceImpl.kt:29, RssFetcher.kt:29, PodcastFeedFetcher.kt:40. Pure use-site-target-wijziging, geen gedragsverandering; bestaande comments blijven staan.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-1402: `@Value(...)` → `@param:Value(...)` gewijzigd op de `ssrfAllowLoopback`-constructorparameter in
  SettingsServiceImpl.kt:29, RssFetcher.kt:29 en PodcastFeedFetcher.kt:40, conform de conventie uit
  docs/factory/technical-spec.md:78 en het patroon in JwtService.kt. Pure use-site-target-wijziging, geen
  gedragswijziging; bestaande comments boven de parameter zijn behouden.
- Gecontroleerd dat er geen ander `@Value`-gebruik in deze drie bestanden zit. De twee overige `@Value`-plekken
  in de repo (PodcastAsyncConfig.kt @Bean-parameter, PodcastTranscriptWorker.kt plain param zonder `val`) zijn
  bekende, reeds gedocumenteerde uitzonderingen en vielen buiten scope van deze subtaak.
- `mvn test` in newsfeedbackend/newsfeedbackend: BUILD SUCCESS, geen failures/errors.

Tester (SF-1403):
- Diff geverifieerd: exact de 3 verwachte regels gewijzigd (SettingsServiceImpl.kt:29, RssFetcher.kt:29,
  PodcastFeedFetcher.kt:40), `@Value(...)` -> `@param:Value(...)`, verder geen wijzigingen buiten worklog.
- Bestaande code-comments boven de parameter zijn ongewijzigd behouden.
- `grep -rn "@Value" src/main/kotlin | grep -v "@param:Value"` geeft alleen de twee bekende, buiten-scope
  uitzonderingen (PodcastAsyncConfig.kt @Bean-parameter, PodcastTranscriptWorker.kt plain ctor-param zonder
  val/var) - geen resterend kaal `@Value`-gebruik op de doelparameters.
- `mvn test` in newsfeedbackend/newsfeedbackend: BUILD SUCCESS, 71/71 tests groen, 0 failures/errors.
- Pure Kotlin-annotatie-syntaxwijziging (use-site-target) zonder gedragsverandering -> geen preview/browsertest
  nodig, conform eerdere vergelijkbare stories (zie agent-tips pnf-adr-audit-worklog-only-verify).
