# SF-2249 - Worklog

Story-context bij eerste pickup:
Valideer feed-URL vóór het bouwen van het HttpRequest (RssFetcher + PodcastFeedFetcher)

Verplaats in `newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/rss/infrastructure/RssFetcher.kt` het `HttpRequest.newBuilder()...build()`-blok (`:53-56`) naar ná het `if (validation is SsrfUrlValidator.ValidationResult.Invalid) { ... }`-blok en vóór `http.send(req, ...)` (`:66`). Doe hetzelfde in `.../podcast_source/infrastructure/PodcastFeedFetcher.kt`: blok `:70-73` naar ná het validatieblok (`:76-82`), vóór `http.send` op `:83`. Verplaats letterlijk, herschrijf niet: headers, timeout en `.GET()` blijven identiek, inclusief het bestaande verschil dat RssFetcher `java.time.Duration.ofSeconds(20)` volledig gekwalificeerd schrijft en PodcastFeedFetcher een geïmporteerde `Duration` gebruikt. Laat het commentaar 'Defense-in-depth: verse DNS-resolutie vlak vóór het versturen' bij de validatie staan. Het `finally`-blok met `callLogger.logCall(...)` en de `catch (e: Exception)`-tak blijven ongewijzigd.

Scherp daarna de twee tests aan die dit gat nu niet bewaken: in `src/test/kotlin/com/vdzon/newsfeedbackend/rss/RssFetcherSsrfTest.kt` voeg je aan `blocks fetch for non-http scheme` (`:36-42`) toe: `assertTrue(loggedCall?.errorMessage?.contains("geblokkeerd") ?: false)`, gelijk aan de loopback-test op `:25`. In `src/test/kotlin/com/vdzon/newsfeedbackend/podcast_source/PodcastFeedFetcherSsrfTest.kt` voeg je aan `blocks fetch for non-http scheme` (`:37-43`) toe: `assertTrue(result.errorMessage?.contains("geblokkeerd") ?: false)`, in de vorm van de loopback-test op `:25`.

Buiten scope: `SsrfUrlValidator` zelf, `ArticleFetcher`, `PodcastAudioDownloader`, `specs/openapi.yaml`, de Flutter-frontends, de e2e-tests en de deploymanifests. Geen documentatiewerk: `specs/backend-technical-spec.md:301` en `:309` blijven correct.

Verifieer zelf vanuit `newsfeedbackend/newsfeedbackend`: `mvn -B --no-transfer-progress clean test | tee /tmp/mvntest.log` → BUILD SUCCESS, `Tests run: 142, Failures: 0, Errors: 0`. Controleer dat beide fetchers nu `blocked SSRF-risky URL file:///etc/passwd: alleen http/https-URLs zijn toegestaan` loggen, dat `grep -c "invalid URI scheme" /tmp/mvntest.log` 0 geeft (baseline 3) en dat `grep -icE 'warning|deprecat|self-attach' /tmp/mvntest.log` 0 blijft - grep hier nooit op `WARN`, de SSRF-tests loggen legitiem WARN-regels. Sluit af met een eigen review-stap op de diff (is het blok echt alleen verplaatst?). Moest er tóch een bestaande test aangepast worden buiten de twee toegevoegde asserties, meld dat expliciet in `docs/stories/worklog/SF-2249-worklog.md`.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-2250 (development) uitgevoerd op branch `ai/SF-2249`.

## SF-2250 — Valideer feed-URL vóór het bouwen van het HttpRequest

Wijzigingen (4 bestanden, alleen backend):

1. `rss/infrastructure/RssFetcher.kt` — het `HttpRequest.newBuilder()...build()`-blok is
   letterlijk verplaatst van vóór het validatieblok naar erná, direct vóór
   `http.send(req, ...)`. Headers, `java.time.Duration.ofSeconds(20)` en `.GET()` zijn
   ongewijzigd; het commentaar "Defense-in-depth: verse DNS-resolutie vlak vóór het
   versturen" is bij de validatie blijven staan. `finally`/`catch` ongewijzigd.
2. `podcast_source/infrastructure/PodcastFeedFetcher.kt` — dezelfde verplaatsing, met de
   geïmporteerde `Duration` intact (het bestaande stijlverschil met RssFetcher is bewust
   behouden).
3. `RssFetcherSsrfTest.kt` — in `blocks fetch for non-http scheme` de assertie
   `assertTrue(loggedCall?.errorMessage?.contains("geblokkeerd") ?: false)` toegevoegd.
4. `PodcastFeedFetcherSsrfTest.kt` — idem met
   `assertTrue(result.errorMessage?.contains("geblokkeerd") ?: false)`.

Waarom: `HttpRequest.Builder.uri(...)` weigert zelf al elk niet-http/https-schema, dus voor
`file:///etc/passwd` gooide de bouwregel een `IllegalArgumentException` vóórdat
`SsrfUrlValidator` draaide. Die belandde in de algemene `catch`, waardoor de eigen weigering
nooit werd gelogd en de `external_calls`-regel `errorMessage = "invalid URI scheme file"`
kreeg in plaats van `"geblokkeerd: alleen http/https-URLs zijn toegestaan"`. Geen
beveiligingsgat (het verzoek werd nooit verstuurd), wel een misleidend audit-spoor.

Bewijs dat de twee nieuwe asserties het gedrag echt bewaken (AC6): met alléén de
testwijzigingen, zónder de productieverplaatsing, faalden precies
`PodcastFeedFetcherSsrfTest.blocks fetch for non-http scheme:42` en
`RssFetcherSsrfTest.blocks fetch for non-http scheme:42` — 6 tests, 2 failures, verder niets.

Verificatie na de volledige wijziging:

- `mvn -B --no-transfer-progress clean test` → exit 0, BUILD SUCCESS,
  `Tests run: 142, Failures: 0, Errors: 0` (AC1).
- Log bevat nu `[RSS] blocked SSRF-risky URL file:///etc/passwd: alleen http/https-URLs zijn
  toegestaan` en dezelfde regel met `[PodcastFeed]` (AC2).
- `grep -c "invalid URI scheme" /tmp/mvntest.log` → **0** (baseline 3) (AC3).
- Stacktrace van `RssFetcherSsrfTest` weg; log 330 regels (was 419) (AC4).
- `grep -icE 'warning|deprecat|self-attach' /tmp/mvntest.log` → **0** (AC5).
- Volledig vangnet `mvn -B --no-transfer-progress clean verify` → exit 0, 142 unit + 77 e2e,
  0 failures / 0 errors; `target/jacoco.exec` (373.557 B) en `target/jacoco-it.exec`
  (9,2 MB) beide geschreven.

Geen enkele bestaande test hoefde aangepast te worden buiten de twee toegevoegde asserties.
Eigen review op de diff: beide productieblokken zijn regel-voor-regel identiek verplaatst,
geen herschrijving. Buiten scope gebleven zoals afgesproken: `SsrfUrlValidator`,
`ArticleFetcher`, `PodcastAudioDownloader`, `specs/*`, de Flutter-apps, e2e en deploy.
