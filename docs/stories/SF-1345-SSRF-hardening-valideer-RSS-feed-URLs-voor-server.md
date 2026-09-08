# SF-1345 - SSRF-hardening: valideer RSS-feed-URLs vóór server-side fetch

## Story

SSRF-hardening: valideer RSS-feed-URLs vóór server-side fetch

<!-- refined-by-factory -->

## Scope

Voeg SSRF-hardening toe voor user-ingevoerde RSS-feed-URLs, uitsluitend binnen de `rss`-module:

1. **Nieuwe validatie-utility** (bijv. `rss`-package of `common`) die een URL-string valideert:
   - Alleen scheme `http` of `https` toegestaan (case-insensitive); overige schemes (`file:`, `ftp:`, `gopher:`, etc.) worden afgewezen.
   - De host wordt DNS-resolved (`InetAddress.getAllByName` of vergelijkbaar); als **één van** de geresolveerde adressen loopback, link-local, site-local/private (RFC1918/ULA), multicast, of anderszins niet-globaal-routeerbaar is, wordt de URL afgewezen.
   - Kan geen enkel adres worden geresolveerd, wordt de URL eveneens afgewezen (fail closed).
2. **Validatie bij opslaan** in `SettingsServiceImpl.saveRssFeeds`: elke URL in `RssFeedsSettings.feeds` wordt gevalideerd vóór opslag; bij afwijzing wordt een `BadRequestException` (bestaande klasse in `common/Exceptions.kt`) gegooid met een duidelijke NL-foutmelding, resulterend in een nette HTTP 400 via de bestaande `GlobalExceptionHandler`.
3. **Defense-in-depth in `RssFetcher.fetch()`**: dezelfde validatie wordt opnieuw uitgevoerd vlak vóór de `http.send(...)`-call (dus ná het opbouwen van het `HttpRequest`, met een verse DNS-resolutie op dat moment). Bij afwijzing: geen fetch uitvoeren, behandelen als fetch-fout (zelfde pad als huidige `catch`/`status="error"` in `fetch()` — loggen via `logFetch(...)` met `errorMessage` en lege `List<RssItem>` als returnwaarde), niet als exception naar de caller (de huidige `fetch()`-caller verwacht geen exceptions; scheduler-context heeft geen HTTP-response om een 400 op te tonen).
4. Buiten scope: `PodcastFeedFetcher` in `podcast_source` (zelfde patroon, apart vervolgticket — zie agent-tip `security/ssrf-rss-podcast-feed-fetch`), en het wijzigen van het huidige redirect-gedrag (`HttpClient.Redirect.ALWAYS`). Een kwaadwillende server die pas ná de validatie via een 3xx-redirect naar een privé-adres doorstuurt, wordt door deze story niet tegengehouden — dat is een bekend restrisico, geen onderdeel van deze scope.

## Acceptance criteria

1. Een `PUT /api/rss-feeds`-call met een feed-URL die geen `http`/`https`-scheme heeft, resulteert in HTTP 400 met een duidelijke NL-foutmelding in `error`; de instellingen worden niet opgeslagen.
2. Een `PUT /api/rss-feeds`-call met een feed-URL waarvan de host resolvet naar een loopback- (`127.0.0.1`, `::1`), link-local- (`169.254.0.0/16`, `fe80::/10`), private- (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, ULA `fc00::/7`) of multicast-IP, resulteert in HTTP 400; de instellingen worden niet opgeslagen.
3. Een `PUT /api/rss-feeds`-call met een geldige publieke http/https-URL slaagt ongewijzigd (HTTP 200, feeds opgeslagen) — bestaand gedrag blijft intact.
4. `RssFetcher.fetch()` voert dezelfde validatie opnieuw uit vlak vóór `http.send(...)`; als de op dat moment geresolveerde host in een geblokkeerde range valt (ook als de URL bij opslaan nog wel geldig was, bijv. door DNS-rebinding), wordt er geen HTTP-request verstuurd, retourneert `fetch()` een lege lijst, en wordt de fetch als `status="error"` gelogd via het bestaande `logFetch`-mechanisme (`external_calls.jsonl`).
5. Bestaande, reeds opgeslagen geldige feed-URLs blijven na deze wijziging zonder functionele regressie ophaalbaar (geen wijziging in parsing/itemlogica van `fetch()`).
6. Unit tests dekken: scheme-afwijzing, elke geblokkeerde IP-range-categorie, geldige publieke URL (accept), en niet-resolvebare host (reject).

## Aannames

- De validatie-utility wordt gedeeld tussen `SettingsServiceImpl` en `RssFetcher` (geen dubbele implementatie) — exacte locatie (`rss`-package vs. `common`) is een implementatiedetail voor de developer, mits het geen Modulith-grens overtreedt.
- IPv4-mapped IPv6-adressen (bijv. `::ffff:127.0.0.1`) worden door dezelfde IP-range-check afgevangen als hun IPv4-equivalent (via `InetAddress`-normalisatie), zodat deze bekende bypass-vector ook geblokkeerd is.
- Bij meerdere geresolveerde adressen voor één host (round-robin DNS) is het al voldoende dat één van de adressen in een geblokkeerde range valt om de hele URL af te wijzen (conservatieve/fail-safe keuze).
- Het bestaande gedrag van `RssFetcher.fetch()` bij fouten (lege lijst retourneren, loggen, geen exception naar caller) blijft ongewijzigd voor de nieuwe SSRF-afwijzing — er wordt geen nieuw foutpad/exception-type richting de scheduler geïntroduceerd.
- Redirect-gedrag (`HttpClient.Redirect.ALWAYS`) wordt niet aangepast in deze story; volledige bescherming tegen SSRF-via-redirect valt buiten scope (zie hierboven).
- `docs/factory/` is al compleet (sinds SF-220); geen apart documentatie-acceptatiecriterium nodig.

## Eindsamenvatting

I have enough information from the worklog to write the summary. No further code changes needed.

## Eindsamenvatting SF-1345 — SSRF-hardening RSS-feed-URLs

**Wat is gebouwd**
- Nieuwe gedeelde validatie-utility `common/SsrfUrlValidator.kt`: staat alleen `http`/`https`-schemes toe en blokkeert URLs waarvan de (DNS-geresolveerde) host loopback, link-local, private (RFC1918/IPv6 ULA) of multicast is — inclusief bescherming tegen IPv4-mapped-IPv6-bypass (bv. `::ffff:127.0.0.1`). Fail-closed als geen adres resolvet.
- Validatie is ingebouwd op twee plekken: bij opslaan (`SettingsServiceImpl.saveRssFeeds`, geeft `BadRequestException` → nette HTTP 400 met NL-foutmelding, niets opgeslagen bij afwijzing) én als defense-in-depth vlak vóór de daadwerkelijke fetch (`RssFetcher.fetch()`, met verse DNS-resolutie tegen DNS-rebinding — bij afwijzing lege lijst + `status="error"` gelogd, geen exception naar de caller).
- `specs/openapi.yaml` bijgewerkt met de nieuwe 400-response op `PUT /api/rss-feeds`.
- Bewust buiten scope gehouden (conform story): `PodcastFeedFetcher` (apart vervolgticket) en het bestaande redirect-gedrag (`HttpClient.Redirect.ALWAYS`) — SSRF-via-redirect blijft een bekend restrisico.

**Belangrijke keuze onderweg**
- E2e-tests (`RssRefreshE2eTest`/`SettingsE2eTest`) bleken de nieuwe loopback-blokkade zelf te raken (test-server draait op `127.0.0.1`). Opgelost met een `allowLoopback`-escape-hatch, default `false`, alleen aangezet via `app.security.ssrf.allow-loopback=true` in de e2e-testbasis — dus geen productierisico.

**Getest**
- Unit: 65 tests groen (o.a. 22 voor `SsrfUrlValidator` incl. alle geblokkeerde IP-categorieën, scheme-afwijzing en niet-resolvebare host), `ModuleStructureTest` groen (geen Modulith-schending).
- Story-brede test tegen live preview: alle acceptatiecriteria bevestigd via directe API-calls — non-http scheme, elke geblokkeerde IP-range (incl. AWS/GCP metadata-endpoint `169.254.169.254`), geldige publieke URL blijft werken, geen partiële opslag bij afwijzing, geen regressie in bestaande feed-parsing.
- AC4 (defense-in-depth in `RssFetcher.fetch()`) niet end-to-end via de scheduler getest (geen HTTP-respons om te observeren) maar bevestigd via gerichte unit-test.
- `mvn verify` (Testcontainers-e2e) kon in de sandbox niet lokaal draaien (Docker ontbrak); dit is via de factory-verify-gate elders afgedekt.

Geen bugs gevonden; oordeel van review en test: akkoord/tested, geen blockers.
