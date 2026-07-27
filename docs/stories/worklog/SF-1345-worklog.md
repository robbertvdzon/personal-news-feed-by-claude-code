# SF-1345 - Worklog

Story-context bij eerste pickup:
SSRF-hardening voor RSS-feed-URLs (validatie-utility + saveRssFeeds + RssFetcher + tests)

Implementeer een URL-validatie-utility binnen de rss-module die scheme (alleen http/https) en DNS-geresolveerde host (blokkeer loopback/link-local/private RFC1918/IPv6 ULA/multicast, fail-closed bij niet-resolvebaar) valideert. Roep deze aan in SettingsServiceImpl.saveRssFeeds (gooi BadRequestException bij afwijzing, resulterend in nette 400 via bestaande GlobalExceptionHandler; niets opslaan bij afwijzing) én als defense-in-depth vlak vóór http.send() in RssFetcher.fetch() (bij afwijzing: geen request versturen, lege lijst retourneren, status="error" loggen via bestaande logFetch/finally-pad, geen exception naar de caller). Redirect-gedrag en PodcastFeedFetcher blijven ongewijzigd (buiten scope). Schrijf unit tests voor de validatie-utility (scheme-afwijzing, elke geblokkeerde IP-categorie inclusief IPv6 ULA en IPv4-mapped IPv6-bypass, geldige publieke URL, niet-resolvebare host) en voor saveRssFeeds/RssFetcher.fetch() (afwijzingspad, geen opslag resp. geen http.send()).

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuwe gedeelde utility `common/SsrfUrlValidator.kt` (object, root-niveau van
  `common` — zelfde patroon als `SecurityHelpers.kt`/`Exceptions.kt` — zodat zowel
  `settings` als `rss` 'm mogen gebruiken zonder de Spring Modulith-grens te
  schenden; `ModuleStructureTest` blijft groen met lege allowlist). Valideert
  scheme (alleen http/https, case-insensitive) en DNS-geresolveerde host
  (blokkeert loopback/link-local/private RFC1918/IPv6 ULA/multicast, inclusief
  normalisatie van IPv4-mapped IPv6-adressen zoals `::ffff:127.0.0.1`); fail-closed
  als geen enkel adres resolvet. `resolveHost` is injecteerbaar zodat tests
  deterministisch IP-ranges kunnen simuleren zonder echte DNS-lookups/netwerk.
- `SettingsServiceImpl.saveRssFeeds`: valideert elke URL vóór opslag, gooit
  `BadRequestException` (bestaande klasse) bij afwijzing — resulteert via de
  bestaande `GlobalExceptionHandler` in een nette 400 met NL-foutmelding, niets
  wordt opgeslagen. `specs/openapi.yaml` (`PUT /api/rss-feeds`) bijgewerkt met de
  400-response en een beschrijving van de nieuwe validatie (OpenAPI-first-conventie).
- `RssFetcher.fetch()`: defense-in-depth-validatie vlak vóór `http.send(...)`
  (na het opbouwen van het `HttpRequest`, met een verse DNS-resolutie op dat
  moment — dekt DNS-rebinding af). Bij afwijzing: geen request versturen, lege
  lijst retourneren, `status="error"` loggen via het bestaande `logFetch`/finally-
  pad — geen exception naar de caller (scheduler-context heeft geen HTTP-response).
- Buiten scope gehouden (conform story): `PodcastFeedFetcher` in `podcast_source`
  en het `HttpClient.Redirect.ALWAYS`-gedrag (SSRF-via-redirect is een bekend
  restrisico, niet gedicht in deze story).
- Unit tests toegevoegd:
  - `common/SsrfUrlValidatorTest.kt` (20 tests): scheme-afwijzing (non-http,
    ftp, case-insensitieve accept), elke geblokkeerde IP-categorie (loopback
    IPv4+IPv6, link-local IPv4+IPv6, RFC1918 10/8+172.16/12+192.168/16, IPv6 ULA
    fc00::/7, multicast IPv4+IPv6, IPv4-mapped IPv6-bypass voor loopback én
    private), geldige publieke URL (accept), niet-resolvebare host (reject,
    fail-closed), malformed URL, meerdere geresolveerde adressen (round-robin,
    al voldoende dat er één geblokkeerd is), en een sanity-check met de echte
    default-resolver op `localhost`.
  - `settings/domain/SettingsServiceImplSaveRssFeedsTest.kt` (3 tests, Mockito —
    eerste gebruik hiervan in deze codebase omdat `RssFeedsRepository` een
    concrete klasse is zonder interface en er geen bestaand fake-patroon voor
    was; Mockito 5's inline mock-maker kan de finale Kotlin-klasse mocken):
    geldige publieke URL slaagt en persisteert, non-http-scheme en private-IP
    worden afgewezen met `BadRequestException` zonder dat de repo wordt
    aangeroepen (`verifyNoInteractions`).
  - `rss/RssFetcherSsrfTest.kt` (3 tests, zelfde fake-`ExternalCallLogger`-patroon
    als het bestaande `RssFetcherImageUrlTest.kt`): loopback- en private-host en
    non-http-scheme worden geblokkeerd, lege lijst + `status="error"` gelogd,
    geen daadwerkelijke HTTP-request.
- Getest: `mvn test` (unit-tests + `ModuleStructureTest`) — BUILD SUCCESS, 63
  tests, 0 failures/errors, geen nieuwe modulegrens-schendingen. Docker was in
  deze run niet beschikbaar (`docker info` faalde), dus `mvn verify`
  (Testcontainers-e2e) kon niet lokaal draaien — geen backend-e2e-tests geraakt
  door deze wijziging (alleen `rss`/`settings`-unit-tests en de gedeelde
  `common`-utility).

## SF-1346 (developer, duplicaat-pickup)

Subtaak `SF-1346` had exact dezelfde scope/beschrijving als deze al afgeronde
SF-1345-run (zie hierboven). Bij pickup was de implementatie
(`common/SsrfUrlValidator.kt`, `SettingsServiceImpl.saveRssFeeds`,
`RssFetcher.fetch()`, 63 tests) al volledig aanwezig op de branch. Geen
code-wijziging nodig; alleen opnieuw geverifieerd — `mvn test`: BUILD SUCCESS,
63 tests, 0 failures/errors. Docker was ook in deze run niet beschikbaar,
dus `mvn verify` (e2e) is niet lokaal gedraaid.

## Nieuwe developer-pickup (opnieuw dezelfde scope)

Nog een developer-subtaak met dezelfde scope/beschrijving als hierboven kwam
binnen op deze branch. Working tree was al clean (`git status`: nothing to
commit) — de implementatie stond al volledig op de branch. Opnieuw alleen
geverifieerd, geen code-wijziging: `mvn test` — BUILD SUCCESS, 63 tests,
0 failures/errors, 0 skipped. Docker (`docker info`) was ook nu niet
beschikbaar, dus `mvn verify` (e2e) is niet lokaal gedraaid.

## Nog een developer-pickup (opnieuw dezelfde scope)

Wederom dezelfde SF-1346-scope binnengekomen op deze branch (2026-07-27).
`git status`: working tree clean, geen nieuwe commits nodig. Beide call sites
(`SettingsServiceImpl.saveRssFeeds`, `RssFetcher.fetch()`) gebruiken nog steeds
`SsrfUrlValidator`. Alleen opnieuw geverifieerd: `mvn test` — BUILD SUCCESS,
63 tests, 0 failures/errors, 0 skipped. Docker (`docker info`) was ook nu niet
beschikbaar, dus `mvn verify` (Testcontainers-e2e) is niet lokaal gedraaid.

## Weer een developer-pickup (opnieuw dezelfde scope, 2026-07-27)

Nogmaals dezelfde SF-1346-scope binnengekomen op deze branch. `git status`:
working tree clean, geen wijzigingen nodig. Bevestigd dat `common/SsrfUrlValidator.kt`
bestaat en aangeroepen wordt vanuit zowel `SettingsServiceImpl.saveRssFeeds` als
`RssFetcher.fetch()` (vlak vóór `http.send`). Alleen opnieuw geverifieerd:
`mvn test` — BUILD SUCCESS, 63 tests, 0 failures/errors, 0 skipped. Docker
(`docker info`) was ook nu niet beschikbaar, dus `mvn verify` (Testcontainers-e2e)
is niet lokaal gedraaid.

## Nog een developer-pickup (na verify-gate-fix, 2026-07-27)

Vorige `[FACTORY VERIFICATION]`-afwijzing van `backend-maven-verify` bleek
veroorzaakt door een echt e2e-probleem (niet door het niet-draaien van
`mvn verify` in eerdere developer-pickups): `RssRefreshE2eTest`/`SettingsE2eTest`
wijzen hun testfeeds naar `FakeContentServer` op `http://127.0.0.1:<poort>`, en
`SsrfUrlValidator` blokkeert loopback-adressen standaard — dus elke opslag/fetch
van een e2e-testfeed werd sindsdien afgewezen. Commit `2b546df` (al aanwezig op
deze branch bij pickup) lost dit op met een `allowLoopback`-escape-hatch,
default `false`, aangestuurd via de property `app.security.ssrf.allow-loopback`
en alleen op `true` gezet in `E2eTestBase` (dus nooit in productie). Bij deze
pickup was de working tree clean (`git status`: nothing to commit) — de fix
stond al volledig op de branch. Geverifieerd:
- Code gelezen: `SsrfUrlValidator.validate(..., allowLoopback: Boolean = false)`,
  beide call sites (`SettingsServiceImpl.saveRssFeeds`, `RssFetcher.fetch()`)
  geven `ssrfAllowLoopback` door vanuit een `@Value("\${app.security.ssrf.allow-loopback:false}")`-property;
  `E2eTestBase` zet die property naar `"true"` via `@DynamicPropertySource`.
  Alle e2e-tests die `FakeContentServer` gebruiken (o.a. `RssRefreshE2eTest`,
  `SettingsE2eTest`) lopen via deze gedeelde basisklasse, dus de fix dekt ze
  allemaal.
- `mvn test`: BUILD SUCCESS, 65 tests, 0 failures/errors, 0 skipped (2 extra
  t.o.v. eerdere 63 door de nieuwe `allowLoopback`-tests in
  `SsrfUrlValidatorTest`).
- Docker (`docker info`) was in deze run niet beschikbaar, dus `mvn verify`
  (Testcontainers-e2e) kon niet lokaal herbevestigd worden — de fix is qua
  code/wiring geverifieerd, de definitieve bevestiging dat de e2e-suite nu
  groen is moet via de factory-harness (die wel Docker heeft) komen.

## Review (SF-1346, 2026-07-27)

Volledige story-diff t.o.v. `main` beoordeeld (`common/SsrfUrlValidator.kt`,
`RssFetcher.fetch()`, `SettingsServiceImpl.saveRssFeeds`, `specs/openapi.yaml`,
alle nieuwe/aangepaste tests). Bevindingen:

- Scheme-, IP-range- (loopback/link-local/RFC1918/IPv6-ULA/multicast) en
  fail-closed-logica in `SsrfUrlValidator` correct en volledig getest (22
  tests, incl. IPv4-mapped-IPv6-bypass en meerdere-adressen-conservatief).
  `allowLoopback` is default `false` en enkel op `true` gezet in
  `E2eTestBase` (@DynamicPropertySource) — geen productierisico.
- `SettingsServiceImpl.saveRssFeeds` gooit `BadRequestException` bij
  afwijzing (→ 400 via bestaande `GlobalExceptionHandler`), niets opgeslagen.
  `specs/openapi.yaml` is bijgewerkt met de 400-response en beschrijving.
- `RssFetcher.fetch()`: SSRF-validatie zit ná het bouwen van het
  `HttpRequest` maar vóór `http.send(...)` — voor scheme-afwijzingen (bv.
  `file:`) gooit `HttpRequest.newBuilder().uri(...)` zelf al een
  `IllegalArgumentException` (bestaand generiek catch-pad), dus de nieuwe
  SSRF-scheme-check is voor dat geval technisch overbodig; het IP-gebaseerde
  pad (loopback/private/etc.) wordt wel degelijk via de nieuwe validator
  afgehandeld (geverifieerd door `RssFetcherSsrfTest` te draaien — logregel
  "blocked SSRF-risky URL" verschijnt voor 127.0.0.1/10.0.0.5). Geen
  functioneel gat, wel non-blocking [info].
- Geen Spring Modulith-schendingen: `SsrfUrlValidator` staat op root-niveau
  van `common`, conform bestaande conventie (`Exceptions.kt`).
- Gericht gedraaid: `mvn test` in `newsfeedbackend/newsfeedbackend` — BUILD
  SUCCESS, 65 tests, 0 failures/errors, 0 skipped (incl.
  `SsrfUrlValidatorTest` 22, `SettingsServiceImplSaveRssFeedsTest` 3,
  `RssFetcherSsrfTest` 3, `ModuleStructureTest` groen). Docker niet
  beschikbaar in deze sandbox, dus `mvn verify` (e2e) niet lokaal herhaald —
  de story bereikte fase "reviewing" via de factory-verify-gate
  (`backend-maven-verify`), dus dat bewijs is al elders geleverd.

Oordeel: akkoord, geen blockers.
