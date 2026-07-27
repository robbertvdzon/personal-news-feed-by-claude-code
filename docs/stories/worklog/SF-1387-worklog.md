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

## Tester-verificatie (SF-1389)

- Code-inspectie: `PodcastFeedFetcher.fetch()` en `SettingsServiceImpl.savePodcastFeeds` zijn
  1-op-1 mirrors van `RssFetcher.fetch()` / `saveRssFeeds` (zelfde `@Value`-property, zelfde
  `SsrfUrlValidator.validate`-call, zelfde `geblokkeerd`-foutafhandeling). Nieuwe tests
  `SettingsServiceImplSavePodcastFeedsTest` en `PodcastFeedFetcherSsrfTest` dekken de 3
  AC-scenario's (geldige URL / non-http(s)-scheme / private-adres) net als hun RSS-tegenhangers.
- `mvn test` in `newsfeedbackend/newsfeedbackend`: BUILD SUCCESS, 71/71 tests groen, 0
  failures/errors (incl. `ModuleStructureTest`, `SsrfUrlValidatorTest`, beide nieuwe
  podcast-SSRF-tests en de bestaande RSS-SSRF-tests). Docker/Testcontainers niet beschikbaar in
  deze omgeving → `mvn verify`/e2e niet lokaal herhaald (bekende omgevingsbeperking, geen
  wijziging aan gedrag).
- Live geverifieerd op preview `pnf-pr-189` (backend-only diff, directe API-calls, wegwerp-account
  `tester_sf-1387` want de namespace-secret-read gaf Forbidden — TESTER_USERNAME/PASSWORD niet
  resolvebaar): `PUT /api/podcast-feeds` met `file:///etc/passwd` → 400, `http://127.0.0.1:1/...`
  (loopback) → 400 ("geblokkeerd: ... loopback"), `http://10.0.0.5/...` (RFC1918) → 400
  ("geblokkeerd: ... private"), `http://169.254.169.254/...` (link-local/metadata-endpoint) → 400
  ("geblokkeerd: ... link-local"). Na elke afwijzing bleef `GET /api/podcast-feeds` leeg (geen
  partiële/ongeldige persist). Een geldige publieke podcast-feed-URL
  (`https://feeds.megaphone.fm/replyall`) gaf 200 en bleef persistent via GET — bestaand
  succesvol save/fetch-pad ongewijzigd. Feeds teruggezet naar `[]` en wegwerp-account via
  `DELETE /api/account/me` opgeruimd na afloop; geen resterende testdata in de preview-DB.
- Geen frontend-wijziging in deze story (`PodcastFeedsController` ongewijzigd) → geen
  browser-/Playwright-test nodig.
