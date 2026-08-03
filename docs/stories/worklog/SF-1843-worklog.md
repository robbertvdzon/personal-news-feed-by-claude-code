# SF-1843 - Worklog

Story-context bij eerste pickup:
SSRF-validatie op de artikel-URL in ArticleFetcher + unit-test + spec-updates

Voeg aan rss/infrastructure/ArticleFetcher.kt een constructor-parameter @param:Value("${app.security.ssrf.allow-loopback:false}") private val ssrfAllowLoopback: Boolean = false toe volgens exact het patroon van RssFetcher.kt:29 / PodcastFeedFetcher.kt:40 (met korte WHY-comment, geen nieuwe configuratie). Roep in fetchPlainText, vóór het opbouwen/versturen van het HttpRequest, SsrfUrlValidator.validate(url, allowLoopback = ssrfAllowLoopback) aan. Bij ValidationResult.Invalid: WARN-log met URL en reden (analoog aan RssFetcher.kt:61), status="error", errorMessage="geblokkeerd: <reden>", en lever null op. LET OP: fetchPlainText logt via `return try { ... }.also { logFetch(...) }` - een `return null` binnen de try-tak slaat .also over en logt dan GEEN ExternalCall; de afwijzing moet als waarde uit de expressie komen zodat logFetch precies één keer loopt met units=0 en subject=url.take(120). Geldige publieke URLs blijven ongewijzigd (fetch, stripHtml, take(maxChars), status="ok", units=aantal tekens); geen exception naar boven, FeedItemGenerator valt via de bestaande ?: rss.snippet terug. Schrijf src/test/kotlin/com/vdzon/newsfeedbackend/rss/ArticleFetcherSsrfTest.kt, 1-op-1 gespiegeld op RssFetcherSsrfTest.kt (anonieme object : ExternalCallLogger, JUnit5 assertEquals/assertTrue, geen Mockito, geen Spring-context, ArticleFetcher geconstrueerd met alleen de logger zodat de default ssrfAllowLoopback=false geldt). Cases: http://127.0.0.1:1/artikel.html, http://10.0.0.5/artikel.html, file:///etc/passwd en http://169.254.169.254/latest/meta-data/; elke case asserteert null plus status="error", minimaal één case ook "geblokkeerd" in errorMessage. Werk specs/backend-functional-spec.md §7.5 bij (validatie zit nu op drie plekken in de RSS-tak: bij opslaan, vlak vóór de feed-fetch en vlak vóór de artikel-fetch in ArticleFetcher.fetchPlainText, met fallback naar rss.snippet; restrisico SSRF-via-redirect blijft expliciet onveranderd) en voeg in specs/backend-technical-spec.md (testlijst rond r296-304) een regel voor rss/ArticleFetcherSsrfTest.kt toe in de bestaande vorm met story-referentie SF-1843. Buiten scope: PodcastAudioDownloader.kt, Redirect.ALWAYS, SsrfUrlValidator zelf, FeedItemGenerator, RssFetcher, openapi.yaml, frontend, application.properties/deploy-manifests. Sluit af met een eigen review van de diff en een groene `mvn -B clean verify` zonder nieuwe compile-warnings.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `ArticleFetcher` heeft nu dezelfde `ssrfAllowLoopback`-constructor-parameter als
  `RssFetcher.kt:29` / `PodcastFeedFetcher.kt:40` (`app.security.ssrf.allow-loopback`,
  default `false`, geen nieuwe configuratie).
- `fetchPlainText` valideert de artikel-URL via `SsrfUrlValidator.validate(url,
  allowLoopback = ssrfAllowLoopback)` als allereerste stap in de `try`-tak, dus vóór het
  opbouwen/versturen van het `HttpRequest`. Bij `Invalid`: WARN-log met URL + reden,
  `status="error"`, `errorMessage="geblokkeerd: <reden>"`.
- De afwijzing is bewust een `null`-**waarde** in een `if/else` binnen de expressie: de
  bestaande `.also { logFetch(...) }` moet blijven lopen. Een `return`/`return@fetchPlainText`
  zou dat overslaan en er zou géén `ExternalCall` gelogd worden. Het request-blok is
  daarvoor in de `else`-tak genest; verder is er niets aan de bestaande logica gewijzigd,
  dus het happy path (`stripHtml`, `take(maxChars)`, `status="ok"`, `units=aantal tekens`)
  blijft identiek.
- Nieuwe test `rss/ArticleFetcherSsrfTest.kt`, gespiegeld op `RssFetcherSsrfTest.kt`
  (anonieme `object : ExternalCallLogger`, JUnit5, geen Mockito/Spring). Vier cases:
  loopback, RFC1918, `file://` en het link-local metadata-endpoint. Elke case asserteert
  `null` + precies één gelogde `ExternalCall` met `status="error"`; de loopback-case
  asserteert daarnaast `"geblokkeerd"` in `errorMessage`, `units=0` en `subject=url`.
  De testklasse verzamelt de calls in een lijst (i.p.v. één `var` zoals in
  `RssFetcherSsrfTest`) zodat "precies één log-regel" ook echt aantoonbaar is.
- Specs bijgewerkt: `specs/backend-functional-spec.md` §7.5 (validatie nu op drie plekken
  in de RSS-tak, met fallback naar `rss.snippet`; restrisico SSRF-via-redirect expliciet
  onveranderd) en `specs/backend-technical-spec.md` testlijst (regel voor
  `rss/ArticleFetcherSsrfTest.kt` met SF-1843-referentie).
- E2e blijft groen zonder aanpassing: `E2eTestBase` zet `app.security.ssrf.allow-loopback=true`,
  dus artikel-fetches naar `FakeContentServer` op 127.0.0.1 slagen nog steeds.

Verificatie: `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend` → exit 0,
98 unit-tests + 61 e2e-tests, 0 failures / 0 errors (~3 min). Geen enkele compile-warning
op `src/main`; de ~40 `asText() is deprecated`-warnings in de e2e-tests zijn pre-existing
en niet door deze story geraakt.

## Review (SF-1844)

Volledige story-diff t.o.v. `main` gereviewd (5 bestanden). Akkoord.
- AC 1-11 nagelopen tegen de code: validatie is het eerste statement in de `try`-tak, de
  afwijzing komt als `null`-waarde uit de `if/else` zodat `.also { logFetch(...) }` blijft
  lopen (precies één `ExternalCall`, `status="error"`, `units=0`, `subject=url.take(120)`),
  WARN-log met URL + reden, happy path ongewijzigd. Patroon identiek aan
  `RssFetcher.kt:29` / `PodcastFeedFetcher.kt:40`. Geen module-grensschending
  (`common.SsrfUrlValidator` is bewust root-level en wordt al door `rss` gebruikt).
- Gerichte eigen check: `mvn -B -o test -Dtest=ArticleFetcherSsrfTest` → BUILD SUCCESS,
  4/4 groen, alle vier cases geblokkeerd zonder netwerkcall (loopback, RFC1918, `file://`,
  link-local metadata). Volledige `clean verify` niet herdraaid (harness-geverifieerd).
- Geen scope-creep: alleen de vier bedoelde bestanden + worklog, geen openapi/Flyway/
  frontend/lockfile-wijzigingen.
- [suggestie] `specs/backend-functional-spec.md` §7.5 opent nu met "elke URL waar de server
  zelf naartoe fetcht wordt gevalideerd"; dat is breder dan waar is zolang
  `PodcastAudioDownloader` (enclosure-URL) ongevalideerd blijft. De zin erna scopet het naar
  de RSS-tak, dus niet blokkerend — meenemen in de vervolgstory voor
  `PodcastAudioDownloader`.
