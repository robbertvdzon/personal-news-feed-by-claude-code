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
