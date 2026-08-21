# SF-2249 - [Audit] Audit: valideer de feed-URL vóórdat het HttpRequest gebouwd wordt (RssFetcher en PodcastFeedFetcher)

## Story

[Audit] Audit: valideer de feed-URL vóórdat het HttpRequest gebouwd wordt (RssFetcher en PodcastFeedFetcher)

<!-- refined-by-factory -->

## Scope

`RssFetcher.fetch` en `PodcastFeedFetcher.fetch` bouwen het `HttpRequest` op vóórdat ze hun eigen SSRF-controle draaien:

- `rss/infrastructure/RssFetcher.kt:53` bouwt, `:59` valideert
- `podcast_source/infrastructure/PodcastFeedFetcher.kt:70` bouwt, `:76` valideert

Hun twee broertjes doen het andersom en zijn de referentie: `ArticleFetcher.kt:49` → `:56` en `PodcastAudioDownloader.kt:47` → `:55` valideren eerst en bouwen pas daarna.

`HttpRequest.Builder.uri(...)` weigert zelf al alles wat geen `http`/`https` is. Voor een `file://`-URL gooit de bouwregel dus `IllegalArgumentException: invalid URI scheme file` vóórdat `SsrfUrlValidator` draait; die uitzondering valt in de algemene `catch` onderaan. Gevolgen, alle drie zichtbaar in de output van `mvn test`:

1. De eigen weigering wordt nooit uitgesproken. `ArticleFetcher` logt voor dezelfde invoer `[ArticleFetcher] blocked SSRF-risky URL file:///etc/passwd: alleen http/https-URLs zijn toegestaan`; deze twee loggen `failed to fetch file:///etc/passwd: invalid URI scheme file`.
2. Het audit-spoor klopt niet: de `external_calls`-regel krijgt `errorMessage = "invalid URI scheme file"` (een JDK-melding) in plaats van `"geblokkeerd: alleen http/https-URLs zijn toegestaan"`. Wie in dat log zoekt naar geweigerde URL's vindt deze categorie niet.
3. De tests vangen het niet. In `RssFetcherSsrfTest.kt` en `PodcastFeedFetcherSsrfTest.kt` asserteert de loopback-test op `contains("geblokkeerd")`, maar de `file://`-test (`:36-42` in beide) doet dat als enige níét — en dat kán ook niet, want die assertie zou vandaag falen. Beide tests zijn groen om de verkeerde reden.

Dit is géén gat in de beveiliging: het verzoek wordt niet verstuurd, de fetch geeft een leeg resultaat en de status is `error`. Loopback, privé RFC1918 en link-local lopen wél gewoon door de eigen validator. Het is een volgordefout met een misleidend spoor en een test die hem niet bewaakt.

### Gevraagde wijziging

1. **`rss/infrastructure/RssFetcher.kt`**: verplaats het `HttpRequest.newBuilder()...build()`-blok (`:53-56`) naar ná het validatieblok, dus tussen de afsluitende `}` van `if (validation is ...Invalid) { ... }` en de `http.send(req, ...)` op `:66`. Laat het commentaar "Defense-in-depth: verse DNS-resolutie vlak vóór het versturen" bij de validatie staan — dat blijft kloppen.
2. **`podcast_source/infrastructure/PodcastFeedFetcher.kt`**: dezelfde verplaatsing van `:70-73` naar ná het validatieblok, vóór `http.send` op `:83`.
3. **`RssFetcherSsrfTest.kt`**: voeg aan `blocks fetch for non-http scheme` de assertie `assertTrue(loggedCall?.errorMessage?.contains("geblokkeerd") ?: false)` toe, gelijk aan wat de loopback-test op `:25` al doet.
4. **`PodcastFeedFetcherSsrfTest.kt`**: idem in `blocks fetch for non-http scheme`, in de vorm die daar gebruikt wordt: `assertTrue(result.errorMessage?.contains("geblokkeerd") ?: false)`, zoals de loopback-test op `:25`.

### Buiten scope

Bewust niet meenemen: de regex/validatielogica in `SsrfUrlValidator` zelf, de andere twee fetchers (die zijn al goed), en het verschil dat `RssFetcher.kt:95` de exception wél aan `log.warn` meegeeft en `PodcastFeedFetcher.kt:111` niet — dat verschil wordt na deze wijziging vanzelf onzichtbaar voor dit geval. Documentatie hoeft niet mee: `specs/backend-technical-spec.md:301` en `:309` beschrijven de check als "vlak vóór `http.send(...)`" en blijven na de verplaatsing correct.

## Acceptance criteria

1. Vanuit `newsfeedbackend/newsfeedbackend`: `mvn -B --no-transfer-progress clean test | tee /tmp/mvntest.log` geeft BUILD SUCCESS met `Tests run: 142, Failures: 0, Errors: 0`. Er hoeft geen enkele bestaande test aangepast te worden behalve de twee toegevoegde asserties; moet dat toch, meld dat expliciet in het worklog.
2. In `/tmp/mvntest.log` staat voor beide fetchers `blocked SSRF-risky URL file:///etc/passwd: alleen http/https-URLs zijn toegestaan` (dus `[RSS] blocked ...` en `[PodcastFeed] blocked ...`), en niet langer `failed to fetch ...: invalid URI scheme file`.
3. `grep -c "invalid URI scheme" /tmp/mvntest.log` geeft **0** (baseline is 3: twee WARN-regels plus de header van de stacktrace).
4. De stacktrace van `RssFetcherSsrfTest` is uit de log verdwenen; de log is daardoor ongeveer 89 regels korter (gemeten: 419 → 330).
5. `grep -icE 'warning|deprecat|self-attach' /tmp/mvntest.log` geeft nog steeds **0**. Grep hier nooit op `WARN` — de SSRF-tests loggen legitiem `WARN `-regels.
6. De twee toegevoegde asserties bewaken het gedrag echt: met alleen de testwijziging en zónder de productieverplaatsing falen precies `RssFetcherSsrfTest.blocks fetch for non-http scheme` en `PodcastFeedFetcherSsrfTest.blocks fetch for non-http scheme`.
7. Geen gedragsverandering voor geldige `http`/`https`-URL's: dezelfde items, dezelfde `external_calls`-regel, dezelfde statuscodes. De overige tests in beide SSRF-testklassen (loopback, RFC1918) blijven ongewijzigd groen.

## Aannames

- Puur een volgordewijziging: het `HttpRequest`-blok wordt letterlijk verplaatst, niet herschreven. De headers, timeouts en `.GET()` blijven identiek, inclusief het verschil dat `RssFetcher` `java.time.Duration.ofSeconds(20)` volledig gekwalificeerd schrijft en `PodcastFeedFetcher` een geïmporteerde `Duration` gebruikt.
- Het `finally`-blok met `callLogger.logCall(...)` blijft ongewijzigd; door de verplaatsing verandert alleen de waarde van `errorMessage` voor het `file://`-geval.
- De `catch (e: Exception)`-tak blijft bestaan en blijft nodig voor echte netwerk- en parse-fouten; hij vangt na deze wijziging alleen het niet-http-scheme niet meer af.
- Het exacte regelaantal van de log is omgevingsafhankelijk; criterium 4 is bedoeld als orde-van-grootte-controle. Criterium 3 (`grep -c` → 0) is de harde toets.
- Geen wijziging aan `specs/openapi.yaml`, de Flutter-frontends, de e2e-tests of de deploymanifests. E2e blijft ongemoeid: `E2eTestBase` zet `allow-loopback=true`, wat losstaat van de scheme-controle.

## Eindsamenvatting

Alles gelezen: `.task.md`, het worklog en de volledige diff (`main...HEAD`, 4 codebestanden + worklog).

## Eindsamenvatting SF-2249 — Valideer de feed-URL vóórdat het HttpRequest gebouwd wordt

### Wat is gebouwd
In `RssFetcher.kt` en `PodcastFeedFetcher.kt` is het `HttpRequest.newBuilder()...build()`-blok verplaatst van vóór naar ná de SSRF-validatie, direct vóór `http.send(...)`. Daarmee volgen beide fetchers nu dezelfde volgorde als hun referenties `ArticleFetcher` en `PodcastAudioDownloader`.

Aanleiding: `HttpRequest.Builder.uri(...)` weigert zelf al elk niet-`http`/`https`-schema. Voor `file:///etc/passwd` gooide de bouwregel dus een `IllegalArgumentException` vóórdat `SsrfUrlValidator` draaide. Die viel in de algemene `catch`, waardoor de eigen weigering nooit werd gelogd en de `external_calls`-auditregel `errorMessage = "invalid URI scheme file"` (JDK-melding) kreeg in plaats van `"geblokkeerd: alleen http/https-URLs zijn toegestaan"`. Geen beveiligingsgat — het verzoek werd nooit verstuurd — wel een misleidend audit-spoor waarin geweigerde URL's niet terug te vinden waren.

Daarnaast is in `RssFetcherSsrfTest` en `PodcastFeedFetcherSsrfTest` aan de test `blocks fetch for non-http scheme` de assertie op `errorMessage.contains("geblokkeerd")` toegevoegd — die ontbrak als enige en kón er ook niet staan zolang de volgorde fout was; beide tests waren groen om de verkeerde reden.

### Gemaakte keuzes
- Puur een verplaatsing, geen herschrijving: headers, timeout en `.GET()` zijn regel-voor-regel identiek gebleven, inclusief het bestaande stijlverschil (`java.time.Duration` volledig gekwalificeerd in RssFetcher, geïmporteerde `Duration` in PodcastFeedFetcher).
- Het commentaar "Defense-in-depth: verse DNS-resolutie vlak vóór het versturen" blijft bij de validatie staan; dat klopt daar nog steeds.
- `finally`-blok met `callLogger.logCall(...)` en de `catch (e: Exception)`-tak ongewijzigd — die blijft nodig voor echte netwerk- en parsefouten.

### Wat is getest
De tester heeft alle 7 acceptatiecriteria onafhankelijk nagemeten op commit `c496131` en akkoord gegeven, zonder bevindingen:
- `mvn clean test`: BUILD SUCCESS, 142 tests, 0 failures/0 errors. Geen enkele bestaande test hoefde aangepast, buiten de twee toegevoegde asserties.
- Beide fetchers loggen nu `blocked SSRF-risky URL file:///etc/passwd: alleen http/https-URLs zijn toegestaan`; `grep -c "invalid URI scheme"` geeft 0 (zelf gemeten baseline op `main`: 3). De stacktrace uit de testlog is verdwenen (332 t.o.v. 417 regels).
- Bewijskracht van de nieuwe asserties onafhankelijk gereproduceerd: met alléén de testwijziging op `main` falen exact de twee bedoelde tests.
- Volledig vangnet `mvn clean verify`: 142 unit + 77 e2e groen. Loopback- en RFC1918-tests ongewijzigd groen; geen gedragsverandering voor geldige http/https-URL's.
- Live gecontroleerd op preview `pnf-pr-239` (`sha-c496131`): een `file://`-feed wordt al bij opslaan geweigerd door `SettingsServiceImpl`, dus de gewijzigde fetcher-tak is niet via de UI bereikbaar — de fix is per definitie alleen via unit-tests aantoonbaar. Geen screenshots: er is geen frontend geraakt.

### Bewust niet gedaan
`SsrfUrlValidator` zelf, de twee al correcte fetchers, `specs/*` (de omschrijving "vlak vóór `http.send(...)`" blijft kloppen), de Flutter-apps, e2e-tests en deploymanifests. Ook het verschil dat `RssFetcher` de exception wél aan `log.warn` meegeeft en `PodcastFeedFetcher` niet is niet aangeraakt — dat wordt voor dit geval vanzelf onzichtbaar.
