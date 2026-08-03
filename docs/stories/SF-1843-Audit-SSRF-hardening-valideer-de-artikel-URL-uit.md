# SF-1843 - [Audit] SSRF-hardening: valideer de artikel-URL uit een feed-item vóór ArticleFetcher hem ophaalt

## Story

[Audit] SSRF-hardening: valideer de artikel-URL uit een feed-item vóór ArticleFetcher hem ophaalt

<!-- refined-by-factory -->

## Samenvatting

Onze app haalt van elk nieuwsitem het volledige artikel op om er een samenvatting van te maken. Het webadres van dat artikel komt uit de feed zelf, en daar wordt nu niets aan gecontroleerd. Iemand die zelf een account aanmaakt en een eigen feed instelt, kan daarin een adres zetten dat naar interne systemen van de server wijst. De server haalt dat dan netjes op, en de inhoud komt via de samenvatting weer terug in het overzicht van diezelfde persoon.

Voor feed-adressen die een gebruiker zelf intypt bestaat deze controle al. Deze story past dezelfde controle ook toe op het artikeladres uit de feed. Wordt een adres afgewezen, dan wordt er geen verbinding gelegd en gebruikt de app gewoon het korte fragment uit de feed — precies wat er nu ook al gebeurt als een artikel niet opgehaald kan worden. Voor de gebruiker verandert er verder niets zichtbaars.

## Scope

In scope — uitsluitend de `rss`-module:

- `rss/infrastructure/ArticleFetcher.kt`
  - Constructor-parameter toevoegen voor `app.security.ssrf.allow-loopback` volgens exact het patroon van `RssFetcher.kt:29` / `PodcastFeedFetcher.kt:40`: `@param:Value("\${app.security.ssrf.allow-loopback:false}") private val ssrfAllowLoopback: Boolean = false`, met korte WHY-comment in dezelfde stijl.
  - Aan het begin van `fetchPlainText(...)` `SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback)` aanroepen, vóór het opbouwen/versturen van het HTTP-request.
- Nieuwe test `src/test/kotlin/com/vdzon/newsfeedbackend/rss/ArticleFetcherSsrfTest.kt`, in dezelfde vorm als `RssFetcherSsrfTest.kt` (fake `ExternalCallLogger` als anonieme object-expressie, JUnit5 `assertEquals`/`assertTrue`, geen Mockito, geen Spring-context).
- Documentatie:
  - `specs/backend-functional-spec.md` §7.5 — vermelden dat de validatie nu ook op de artikel-URL uit een feed-item zit.
  - `specs/backend-technical-spec.md` testlijst (rond r296-304) — regel voor `ArticleFetcherSsrfTest.kt` toevoegen, in de bestaande vorm inclusief story-referentie.

Buiten scope:

- `podcast_source/infrastructure/PodcastAudioDownloader.kt` — heeft hetzelfde gat voor de enclosure-URL; logische vervolgstory, net zoals SF-1387 op SF-1345 volgde.
- `HttpClient.Redirect.ALWAYS` blijft ongewijzigd; SSRF-via-redirect blijft een bewust geaccepteerd restrisico.
- Geen nieuwe configuratie-property, geen wijziging in `application.properties`, deploy-manifests of `SsrfUrlValidator` zelf.
- Geen wijziging in `FeedItemGenerator.kt`, `RssFetcher.kt`, API-contract (`specs/openapi.yaml`) of frontend.

## Acceptance criteria

1. `ArticleFetcher.fetchPlainText(username, url, maxChars)` valideert `url` via `SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback)` vóórdat er enig HTTP-request wordt verstuurd.
2. Bij `ValidationResult.Invalid` retourneert `fetchPlainText` `null` en wordt er aantoonbaar géén HTTP-request gedaan (geen socketverbinding naar de host).
3. Bij afwijzing wordt precies één `ExternalCall` gelogd via de bestaande `logFetch`-route in dit bestand, met `status = "error"`, een `errorMessage` die `"geblokkeerd"` bevat (analoog aan `RssFetcher.kt:63`), `units = 0` en `subject = url.take(120)`. Let op: de huidige implementatie logt via `return try { … }.also { logFetch(...) }` — een `return` binnen de try-tak zou `.also` overslaan; de afwijzing moet dus als waarde uit de expressie komen zodat de logging gegarandeerd loopt.
4. De afwijzing wordt gelogd op WARN-niveau met URL en reden, analoog aan `RssFetcher.kt:61`.
5. Het gedrag stroomafwaarts blijft ongewijzigd: `FeedItemGenerator.kt:44` valt via de bestaande `?: rss.snippet` terug op het feed-fragment, precies zoals nu al bij een mislukte fetch. Er wordt geen exception naar boven gegooid en de RSS-refresh loopt door.
6. Bij een geldige publieke URL is het gedrag ongewijzigd (fetch, `stripHtml`, `take(maxChars)`, `status = "ok"`, `units = aantal tekens`).
7. `ArticleFetcherSsrfTest.kt` bestaat en dekt minimaal: loopback-host (`http://127.0.0.1:1/artikel.html`), RFC1918-host (`http://10.0.0.5/artikel.html`), niet-http-scheme (`file:///etc/passwd`) en de metadata-endpoint-case (`http://169.254.169.254/latest/meta-data/`, link-local). Elke case asserteert `null` als returnwaarde plus `status = "error"` op de gelogde `ExternalCall`; minimaal één case asserteert ook `"geblokkeerd"` in `errorMessage`.
8. De test construeert `ArticleFetcher` met alleen de logger — de default `ssrfAllowLoopback = false` moet het productiegedrag opleveren zonder Spring-context.
9. Bestaande e2e-tests blijven groen zonder aanpassing: `E2eTestBase.kt:54` zet `app.security.ssrf.allow-loopback=true`, waardoor artikel-fetches naar de `FakeContentServer` op 127.0.0.1 blijven slagen.
10. `specs/backend-functional-spec.md` §7.5 beschrijft dat `SsrfUrlValidator` nu op drie plekken in de RSS-tak zit: bij opslaan van de feed-URL, vlak vóór de feed-fetch, en vlak vóór de artikel-fetch (`ArticleFetcher.fetchPlainText`, tweede-orde-URL uit de feed-inhoud), met de fallback naar `rss.snippet` als gevolg. Het gedocumenteerde restrisico SSRF-via-redirect blijft expliciet als onveranderd staan.
11. `specs/backend-technical-spec.md` noemt `rss/ArticleFetcherSsrfTest.kt` in de testoverzicht-lijst, in dezelfde vorm als de bestaande SSRF-testregels.
12. `mvn -B clean verify` (`.factory/verification.yaml`, backend-maven-verify) slaagt zonder nieuwe compile-warnings.

## Aannames

- Een afgewezen artikel-URL is geen gebruikersfout maar een normale "kon niet ophalen"-situatie: er komt géén 400/exception richting API en de gebruiker ziet enkel een samenvatting op basis van het feed-fragment. Dit spiegelt `RssFetcher`, dat bij afwijzing een lege lijst teruggeeft in plaats van te falen.
- De validatie gebeurt op de originele URL vóór redirects; een 3xx-doorverwijzing naar een privé-adres blijft niet-afgevangen (bestaand, gedocumenteerd restrisico).
- Eén validatie-aanroep per `fetchPlainText`-call is voldoende; er wordt geen caching van validatieresultaten toegevoegd (verse DNS-resolutie per call is juist het punt, i.v.m. DNS-rebinding).
- Podcast-items lopen niet via `ArticleFetcher` (`FeedItemGenerator` gebruikt voor `mediaType == "PODCAST"` het transcript), dus deze wijziging raakt de podcast-tak niet.
- `docs/factory/technical-spec.md:77` beschrijft de conventie al generiek ("valideer zowel bij opslaan als vlak vóór de daadwerkelijke fetch") en verwijst door naar §7.5; die regel hoeft niet mee te wijzigen.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de volledige story-diff gelezen. Hier de eindsamenvatting.

## SF-1843 — SSRF-hardening op de artikel-URL uit een feed-item

### Wat is gebouwd
De backend haalt bij elk nieuwsitem het volledige artikel op om er een AI-samenvatting van te maken. Die artikel-URL komt uit de feed-inhoud zelf en werd tot nu toe nergens gecontroleerd — een gebruiker met een eigen feed kon daar een adres van een intern systeem in zetten, dat de server vervolgens ophaalde en via de samenvatting terugtoonde.

Concreet gewijzigd (4 bestanden + worklog, uitsluitend de `rss`-module):
- `rss/infrastructure/ArticleFetcher.kt` — validatie via `SsrfUrlValidator.validate(...)` als eerste stap van `fetchPlainText`, dus vóór het opbouwen/versturen van het HTTP-request. Constructor-parameter `ssrfAllowLoopback` toegevoegd volgens exact hetzelfde patroon als `RssFetcher` / `PodcastFeedFetcher`; geen nieuwe configuratie-property.
- Nieuwe unit-test `rss/ArticleFetcherSsrfTest.kt` (4 cases, geen Spring/Mockito).
- `specs/backend-functional-spec.md` §7.5 en `specs/backend-technical-spec.md` testlijst bijgewerkt.

### Gemaakte keuzes
- **Afwijzing = "kon niet ophalen", geen fout richting gebruiker.** Bij een geblokkeerde URL geeft `fetchPlainText` `null` terug en valt `FeedItemGenerator` terug op het feed-fragment (`rss.snippet`) — precies zoals nu al gebeurt bij een mislukte fetch. Geen exception, de RSS-refresh loopt gewoon door.
- **De afwijzing komt als waarde uit de bestaande expressie** in plaats van een vroege `return`. Dat was een reële valkuil: de logging loopt via `.also { logFetch(...) }`, en een `return` had die overgeslagen waardoor er géén `ExternalCall` gelogd zou worden. Nu wordt er gegarandeerd precies één regel gelogd (`status="error"`, `units=0`, `errorMessage` met "geblokkeerd") plus een WARN-log met URL en reden.
- **Happy path exact ongewijzigd** — bij een geldige publieke URL is er niets aan de bestaande logica veranderd.

### Wat is getest
- Unit-suite lokaal: **98 tests groen**, waaronder de 4 nieuwe SSRF-cases (loopback `127.0.0.1`, RFC1918 `10.0.0.5`, `file:///etc/passwd`, cloud-metadata `169.254.169.254`). Elke case: `null` terug, precies één foutregel gelogd, geen netwerkverbinding.
- Volledige `mvn -B clean verify` door de developer: exit 0, 98 unit- + 61 e2e-tests, 0 failures, geen nieuwe compile-warnings. De tester kon e2e niet lokaal draaien (geen Docker in de tester-container) en leunt daarvoor op deze verify-run.
- Preview (`pnf-pr-204`, draaide aantoonbaar de story-code): opslaan van een interne feed-URL geeft nog steeds netjes een 400; een publieke feed leverde 20 items met volwaardige AI-samenvattingen — het ophalen van artikelen werkt dus onveranderd. Ook via de UI gecontroleerd. De wegwerp-testaccount is na afloop opgeruimd.
- Review: alle 11 functionele acceptatiecriteria nagelopen tegen de code, akkoord, geen scope-creep.

### Bewust niet gedaan
- **`PodcastAudioDownloader` (de enclosure-URL van een podcast) is niet gehard** — daar zit hetzelfde gat. Dit is expliciet buiten scope gehouden en is de logische vervolgstory. Let op: de spec-tekst in §7.5 opent nu met een iets te brede formulering ("elke URL waar de server zelf naartoe fetcht"); de reviewer signaleerde dit als niet-blokkerend, maar het is wel iets om in die vervolgstory recht te trekken.
- **SSRF-via-redirect blijft niet afgevangen** — een server die pas ná validatie via een 3xx naar een intern adres doorstuurt, komt er nog doorheen. Bekend en gedocumenteerd restrisico; `HttpClient.Redirect.ALWAYS` is bewust ongewijzigd.
- Geen wijziging aan `SsrfUrlValidator` zelf, `FeedItemGenerator`, `RssFetcher`, het API-contract, deploy-manifests of de frontend.
- Niet na te bootsen in de preview: een echte kwaadaardige feed die zelf een intern artikel-adres serveert (vereist een publiek gehoste feed). Dat pad is gedekt door de unit-test plus code-inspectie.

<!-- deploy-summary:start -->
De app controleert nu ook het adres van het artikel dat achter een nieuwsitem zit, voordat hij dat artikel ophaalt. Wordt zo'n adres niet vertrouwd, dan toont de app gewoon het korte fragment uit de feed. Je merkt hier in het dagelijks gebruik niets van; het maakt de app alleen veiliger.
<!-- deploy-summary:end -->
