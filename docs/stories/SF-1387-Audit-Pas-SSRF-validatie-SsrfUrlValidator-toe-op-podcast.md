# SF-1387 - [Audit] Pas SSRF-validatie (SsrfUrlValidator) toe op podcast-feed-URLs, net als bij RSS-feeds

## Story

[Audit] Pas SSRF-validatie (SsrfUrlValidator) toe op podcast-feed-URLs, net als bij RSS-feeds

<!-- refined-by-factory -->

## Scope

Mirror de bestaande RSS-SSRF-hardening (SF-1345) 1-op-1 naar de podcast-feed-flow:

1. **`SettingsServiceImpl.savePodcastFeeds`** (`settings/domain/SettingsServiceImpl.kt:85-88`): valideer elke feed-URL met `SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback)` vóór opslag, analoog aan `saveRssFeeds`. Gooi `BadRequestException` bij een ongeldige URL. Gebruik dezelfde `@Value("${app.security.ssrf.allow-loopback:false}")`-property (al aanwezig als constructor-param in deze class).
2. **`PodcastFeedFetcher.fetch()`** (`podcast_source/infrastructure/PodcastFeedFetcher.kt:60`): voeg dezelfde defense-in-depth-check toe vlak vóór de HTTP-call, analoog aan `RssFetcher.fetch()` — bij een ongeldige URL: loggen, `status="error"`/`errorMessage="geblokkeerd: <reden>"`, en een `FetchResult(ok = false, ...)` teruggeven zonder de HTTP-call uit te voeren. `PodcastFeedFetcher` krijgt hiervoor dezelfde `@Value("${app.security.ssrf.allow-loopback:false}")`-constructor-parameter als `RssFetcher`.
3. Tests: `SettingsServiceImplSavePodcastFeedsTest.kt` (mirror van `SettingsServiceImplSaveRssFeedsTest.kt`) en een SSRF-test voor `PodcastFeedFetcher` (mirror van `RssFetcherSsrfTest.kt`).

Gedrag voor geldige publieke URLs blijft ongewijzigd. `PodcastFeedsController.savePodcastFeeds` (regels 38-53) blijft functioneel ongewijzigd — de synchrone fetch-bij-opslaan gebruikt straks vanzelf de geharde `fetcher.fetch()`, dus de SSRF-oracle via de directe foutmelding wordt gedicht doordat de fetch zelf al blokkeert vóór de HTTP-call plaatsvindt.

## Acceptance criteria

- `SettingsServiceImpl.savePodcastFeeds` valideert elke URL in `settings.feeds.map { it.url }` (let op: `PodcastFeedsSettings.feeds` is `List<PodcastFeed>`, niet `List<String>` zoals bij RSS) met `SsrfUrlValidator.validate`, en gooit `BadRequestException` met een NL-foutmelding (analoog aan de RSS-variant) zodra één URL ongeldig is; bij een ongeldige URL wordt niets opgeslagen (`podcastFeedsRepo` krijgt geen `save`-aanroep).
- `PodcastFeedFetcher` krijgt een `ssrfAllowLoopback`-constructor-parameter (`@Value("${app.security.ssrf.allow-loopback:false}")`, default `false`) en voert in `fetch()` vlak vóór de `http.send(...)`-call dezelfde `SsrfUrlValidator.validate(feedUrl, allowLoopback = ssrfAllowLoopback)`-check uit als `RssFetcher.fetch()`; bij een ongeldige URL wordt geen HTTP-request verstuurd, wordt een `FetchResult(ok = false, ...)` teruggegeven met een `errorMessage` die "geblokkeerd" bevat, en wordt de externe call gelogd met `status = "error"`.
- Geldige publieke http(s)-URLs voor podcast-feeds blijven zonder gedragswijziging werken (bestaande succesvolle save- en fetch-paden blijven identiek).
- Nieuwe test `SettingsServiceImplSavePodcastFeedsTest.kt` mirrort `SettingsServiceImplSaveRssFeedsTest.kt`: dekt (a) opslaan bij geldige publieke URL, (b) afwijzen van een niet-http(s)-scheme zonder opslaan, (c) afwijzen van een URL die naar een private/loopback-adres resolvet zonder opslaan.
- Nieuwe SSRF-test voor `PodcastFeedFetcher` mirrort `RssFetcherSsrfTest.kt`: dekt blokkeren van loopback-host, RFC1918-private-host en non-http-scheme, telkens zonder dat er een HTTP-request wordt verstuurd en met een gelogde `ExternalCall` met `status = "error"`.
- Bestaande tests (inclusief e2e-scenario's die `app.security.ssrf.allow-loopback=true` gebruiken voor de fake content-server) blijven slagen.

## Aannames

- `PodcastFeedsSettings.feeds` is `List<PodcastFeed>` met een `url`-veld; de validatie in `savePodcastFeeds` itereert dus over `settings.feeds.map { it.url }`, analoog aan maar niet identiek aan de `List<String>`-vorm bij RSS.
- De foutmelding-tekst bij `BadRequestException` in `savePodcastFeeds` mag qua bewoording (NL) analoog zijn aan de RSS-variant ("Ongeldige podcast-feed-URL '...': ...") — geen exacte tekst is voorgeschreven in de story.
- `PodcastFeedFetcher` is een `@Component` (geen bestaande constructor-parameter voor deze property); de nieuwe `ssrfAllowLoopback`-parameter wordt op dezelfde manier toegevoegd als bij `RssFetcher`, zodat bestaande Spring-wiring (autowiring via constructor) ongewijzigd blijft werken.
- Geen wijziging aan `PodcastFeedsController.savePodcastFeeds`: de synchrone fetch-bij-opslaan (regels 38-53) blijft de bestaande foutmelding "Kon feed niet ophalen: ..." teruggeven aan de client; het SSRF-risico daarin wordt afgedekt doordat `fetcher.fetch()` zelf nu blokkeert vóórdat er een HTTP-request naar een intern adres kan gaan (dus geen intern-netwerk-informatie meer lekt via de fetch-fout).

## Eindsamenvatting

Voldoende context verzameld. Ik schrijf de eindsamenvatting voor de PO.

## Eindsamenvatting SF-1387: SSRF-validatie op podcast-feed-URLs

**Wat is gebouwd**

De bestaande SSRF-hardening voor RSS-feeds (SF-1345) is 1-op-1 doorgetrokken naar de podcast-feed-flow:

- `SettingsServiceImpl.savePodcastFeeds` valideert nu elke podcast-feed-URL met `SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback)` vóórdat er iets wordt opgeslagen. Bij een ongeldige URL wordt een `BadRequestException` gegooid en blijft `podcastFeedsRepo.save(...)` volledig achterwege — er komt dus geen partiële of ongeldige data in de opslag terecht.
- `PodcastFeedFetcher.fetch()` voert dezelfde SSRF-check nu ook uit, vlak vóór de HTTP-call, met dezelfde `app.security.ssrf.allow-loopback`-property als bij RSS. Bij een geblokkeerde URL wordt geen netwerkverzoek meer verstuurd; in plaats daarvan komt er direct een foutresultaat terug en wordt de blokkade gelogd als `ExternalCall` met status `error`.
- Gedrag voor geldige publieke http(s)-URLs is ongewijzigd gebleven; `PodcastFeedsController` hoefde niet aangepast te worden, omdat de bescherming nu automatisch meelift via de geharde fetcher.

**Gekozen aanpak**

Bewust gekozen om de RSS-implementatie exact te spiegelen (zelfde constructor-parameter, zelfde foutafhandeling, zelfde logpatroon) in plaats van een generieke/gedeelde oplossing te bouwen — dit hield de wijziging klein en consistent met de bestaande RSS-hardening.

**Getest**

- Unit tests: nieuwe test-suites voor zowel het opslaan (`SettingsServiceImplSavePodcastFeedsTest`) als het ophalen (`PodcastFeedFetcherSsrfTest`), elk met scenario's voor een geldige publieke URL, een niet-http(s)-scheme en een private/loopback-adres. Volledige testrun: 71/71 groen.
- Live verificatie op een preview-omgeving via directe API-calls: pogingen met `file://`, loopback (`127.0.0.1`), een privé-netwerkadres (RFC1918) en het cloud-metadata-endpoint (`169.254.169.254`) werden allemaal correct met een 400-foutmelding geblokkeerd, zonder dat er iets werd opgeslagen. Een geldige publieke podcast-feed-URL werkte zoals voorheen. Testdata is na afloop opgeruimd.

**Bewust niet gedaan**

- De Testcontainers/e2e-testsuite (`mvn verify`) kon niet lokaal worden gedraaid omdat Docker niet beschikbaar was in de werkomgeving; dit is een bekende omgevingsbeperking en geen aanwijzing van een probleem — er is niets gewijzigd aan de e2e-configuratie of -wiring.
- Geen frontend-testen, omdat deze story geen frontend-wijzigingen bevat.
