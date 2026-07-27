# SF-1387 - Worklog

Story-context bij eerste pickup:
SSRF-validatie toepassen op podcast-feed-URLs (mirror RSS)

Mirror de RSS-SSRF-hardening (SF-1345) naar de podcast-feed-flow. (1) SettingsServiceImpl.savePodcastFeeds: valideer settings.feeds.map { it.url } met SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback) vóór podcastFeedsRepo.save(...); gooi BadRequestException('Ongeldige podcast-feed-URL ...') bij een ongeldige URL, analoog aan saveRssFeeds. (2) PodcastFeedFetcher: voeg een ssrfAllowLoopback-constructor-parameter toe (@Value("${app.security.ssrf.allow-loopback:false}")) en voer in fetch() vlak vóór http.send(...) dezelfde SsrfUrlValidator-check uit als RssFetcher.fetch(); bij een ongeldige URL: log.warn, status='error', errorMessage='geblokkeerd: <reden>', en direct FetchResult(ok=false, podcastName='', episodes=emptyList(), errorMessage=...) teruggeven zonder HTTP-call (de bestaande finally-logFetch logt dit automatisch als ExternalCall met status='error'). PodcastFeedsController blijft ongewijzigd. Schrijf als onderdeel van dit werk ook de tests: SettingsServiceImplSavePodcastFeedsTest.kt (mirror van SettingsServiceImplSaveRssFeedsTest.kt: geldige URL opgeslagen, non-http(s)-scheme afgewezen zonder opslaan, private/loopback-adres afgewezen zonder opslaan) en een SSRF-test voor PodcastFeedFetcher (mirror van RssFetcherSsrfTest.kt: loopback/RFC1918/non-http geblokkeerd zonder HTTP-call, met gelogde ExternalCall status='error'). Controleer dat bestaande tests, inclusief e2e met allow-loopback=true, blijven slagen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `SettingsServiceImpl.savePodcastFeeds` valideert nu elke `settings.feeds.map { it.url }` met
  `SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback)` vóór
  `podcastFeedsRepo.save(...)`, analoog aan `saveRssFeeds`; gooit `BadRequestException`
  ("Ongeldige podcast-feed-URL '...': ...") bij een ongeldige URL, zonder te saven.
- `PodcastFeedFetcher` heeft nu dezelfde `ssrfAllowLoopback`-constructor-parameter
  (`@Value("${app.security.ssrf.allow-loopback:false}")`, default false) als `RssFetcher`, en
  voert in `fetch()` vlak vóór `http.send(...)` dezelfde SSRF-check uit: bij een ongeldige URL
  wordt geen HTTP-request verstuurd, `log.warn`, `status="error"`,
  `errorMessage="geblokkeerd: <reden>"`, en direct een `FetchResult(ok=false, ...)`
  teruggegeven (bestaande `finally`-`logFetch` logt dit automatisch als `ExternalCall` met
  `status="error"`). `PodcastFeedsController` is ongewijzigd (fetch-bij-opslaan gebruikt nu
  vanzelf de geharde `fetcher.fetch()`).
- Tests toegevoegd: `settings/domain/SettingsServiceImplSavePodcastFeedsTest.kt` (mirror van
  `SettingsServiceImplSaveRssFeedsTest.kt`: geldige URL opgeslagen, non-http(s)-scheme
  afgewezen zonder save, loopback-adres afgewezen zonder save) en
  `podcast_source/PodcastFeedFetcherSsrfTest.kt` (mirror van `RssFetcherSsrfTest.kt`:
  loopback/RFC1918/non-http geblokkeerd zonder HTTP-call, gelogde `ExternalCall`
  `status="error"`).
- `mvn test` (unit + ModuleStructureTest, Maven-root
  `newsfeedbackend/newsfeedbackend/`): BUILD SUCCESS, 71 tests, 0 failures/errors (was 65 vóór
  deze wijziging; +6 nieuwe SSRF-tests voor podcast-feeds).
- Docker was in deze run niet beschikbaar (`docker info` faalt) — `mvn verify` (Testcontainers
  e2e-suite) kon dus niet lokaal gedraaid worden; conform eerdere runs (zie agent-tips) is dit
  omgevingsafhankelijk en geen indicatie van een probleem in de wijziging zelf. De e2e-tests die
  `app.security.ssrf.allow-loopback=true` gebruiken (`E2eTestBase`) zijn functioneel ongewijzigd
  gebleven — geen enkele bestaande property/wiring is aangepast, alleen een nieuwe
  constructor-parameter met dezelfde default toegevoegd aan `PodcastFeedFetcher` (Spring
  autowiring blijft werken zoals bij `RssFetcher`).
