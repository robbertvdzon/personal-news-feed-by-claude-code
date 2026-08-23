# SF-2284 - [Audit] Maak de SSRF-testregel waar: assertie op "geblokkeerd" in alle 14 SSRF-testcases

## Story

[Audit] Maak de SSRF-testregel waar: assertie op "geblokkeerd" in alle 14 SSRF-testcases

<!-- refined-by-factory -->

## Scope

`docs/factory/technical-spec.md` bevat sinds SF-2249 de huisregel dat elke SSRF-testcase in de fetchers asserteert dat `errorMessage` de tekst `"geblokkeerd"` bevat — het bewijs dat de afwijzing van `SsrfUrlValidator` komt en niet van de JDK of een netwerkfout. De bijzin bij die regel claimt dat de regel repo-breed sluit, maar dat is niet zo: geteld over de vier fetcher-SSRF-testklassen hebben 9 van de 14 testcases die assertie en 5 niet.

Stand vandaag (geverifieerd): `PodcastAudioDownloaderSsrfTest.kt` 4 van 4; `RssFetcherSsrfTest.kt` 2 van 3; `PodcastFeedFetcherSsrfTest.kt` 2 van 3; `ArticleFetcherSsrfTest.kt` 1 van 4.

Deze story is puur testwerk plus één doczin. **Geen enkele wijziging aan productiecode.** Alle vier de fetchers zetten op elk geweigerd pad al `errorMessage = "geblokkeerd: ${validation.reason}"` (`ArticleFetcher.kt:53`, `RssFetcher.kt:59`, `PodcastFeedFetcher.kt:76`, `PodcastAudioDownloader.kt:51`), dus alle vijf de nieuwe asserties slagen meteen.

Voeg per genoemde test één assertie toe, in de vorm die in dat bestand al gebruikt wordt:

1. `rss/RssFetcherSsrfTest.kt`, test `blocks fetch for private rfc1918 host` (`:29-34`):
   `assertTrue(loggedCall?.errorMessage?.contains("geblokkeerd") ?: false)` — gelijk aan `:25`.
2. `podcast_source/PodcastFeedFetcherSsrfTest.kt`, test `blocks fetch for private rfc1918 host` (`:30-35`):
   `assertTrue(result.errorMessage?.contains("geblokkeerd") ?: false)` — gelijk aan `:25`.
3. `rss/ArticleFetcherSsrfTest.kt`, drie tests (`:39` RFC1918, `:48` niet-http-schema, `:57` link-local):
   `assertTrue(loggedCalls.single().errorMessage?.contains("geblokkeerd") ?: false)` — gelijk aan `:33`.
   Deze drie zijn de belangrijkste. `ArticleFetcher` valideert vandaag in de goede volgorde (`:49` valideren, `:56` bouwen), maar niets bewaakt dat — precies het gat dat SF-2249 in de twee andere fetchers dichtte.
4. Corrigeer de bijzin in `docs/factory/technical-spec.md` **regel 105** (niet 110; de aangrenzende bullet over de validatievolgorde staat op :104): schrijf op dat SF-2249 de niet-http-cases van twee fetchers aanvulde en dat deze story de resterende vijf cases heeft gelijkgetrokken, zodat de regel nu wél repo-breed sluit. Noem het getal (14 van 14) zodat een volgende lezer het kan narekenen.

Bij de drie nieuwe asserties in `ArticleFetcherSsrfTest` hoort één kort comment dat vastlegt wat ze bewaken: dat de afwijzing van `SsrfUrlValidator` komt en niet van `HttpRequest.Builder.uri(...)`, en dat de assertie dus niet los "opgeruimd" mag worden.

**Buiten scope:** de validatielogica in `SsrfUrlValidator`, de cases in `SsrfUrlValidatorTest` (die testen de validator zelf, niet een fetcher, en kennen het woord "geblokkeerd" per definitie niet), en elke wijziging onder `src/main`.

## Acceptance criteria

1. `mvn -B --no-transfer-progress clean test` vanuit `newsfeedbackend/newsfeedbackend` geeft BUILD SUCCESS met `Tests run: 142, Failures: 0, Errors: 0` — het aantal tests blijft gelijk, er komen alleen asserties bij.
2. Het aantal treffers van `grep -c 'geblokkeerd'` is 3 in `RssFetcherSsrfTest.kt`, 4 in `ArticleFetcherSsrfTest.kt`, 3 in `PodcastFeedFetcherSsrfTest.kt` en 4 in `PodcastAudioDownloaderSsrfTest.kt` — samen 14, één per testcase.
3. `grep -icE 'warning|deprecat|self-attach'` over de testlog geeft nog steeds 0. Grep hier nooit op `WARN` — de SSRF-tests loggen legitiem `WARN `-regels.
4. `git diff --stat` toont uitsluitend drie testbestanden en `docs/factory/technical-spec.md`; geen enkel bestand onder `src/main`. (Het worklog onder `docs/stories/worklog/` valt hier buiten, dat is factory-standaard.)
5. `docs/factory/technical-spec.md` bevat na de wijziging geen zin meer die claimt dat de loopback- en RFC1918-cases de assertie "al deden", en noemt expliciet 14 van 14.

## Aannames

- **Geen productiecodewijziging.** Geverifieerd door de wijziging daadwerkelijk uit te voeren in een kopie: alle vijf de nieuwe asserties slagen vandaag meteen, 142 tests groen, ruisfilter 0, testlog 331 regels (identiek aan de SF-2270-baseline, dus geen extra logruis).
- **De storyregel `technical-spec.md:110` is 5 te hoog**; de te corrigeren zin staat op regel 105. Ga bij het editen op de zinstekst af, niet op het regelnummer.
- **De drie `ArticleFetcherSsrfTest`-asserties bewaken echt.** Aangetoond: met het `HttpRequest.newBuilder().uri(...)` vóór de validatie gezet (de omkering waar de huisregel voor waarschuwt) faalt `blocks article fetch for non-http scheme` met `expected: <true> but was: <false>`; zonder de nieuwe assertie blijft die omkering groen. De RFC1918- en link-local-asserties vangen díé omkering niet — de JDK weigert die URLs niet — maar bewaken wel dat de `error`-status van onze eigen validator komt en niet van een netwerk- of DNS-fout. Beide motieven horen in het comment.
- Er zijn geen nieuwe imports nodig: alle drie de testbestanden gebruiken `assertTrue` al.
- Geen doc- of specplek buiten `docs/factory/technical-spec.md` doet dezelfde onjuiste claim; `specs/backend-technical-spec.md` beschrijft de SSRF-tests zonder uitspraak over de assertiedekking.
- Geen duplicaatstory in `origin/main` (gecheckt t/m 4a50968).

## Eindsamenvatting

Alles gelezen: `.task.md`, het worklog, de volledige diff en de review-/testnotities. Hier de eindsamenvatting.

## Eindsamenvatting SF-2284 — SSRF-testregel repo-breed sluitend maken

**Wat er is gebouwd**

`docs/factory/technical-spec.md` bevat sinds SF-2249 de huisregel dat elke SSRF-testcase in de fetchers asserteert dat `errorMessage` het woord `"geblokkeerd"` bevat — het bewijs dat de afwijzing van onze eigen `SsrfUrlValidator` komt en niet van de JDK of een netwerkfout. In werkelijkheid dekte die regel maar 9 van de 14 testcases. Die vijf gaten zijn nu gedicht:

- `RssFetcherSsrfTest.kt` — RFC1918-case (+1 assertie)
- `PodcastFeedFetcherSsrfTest.kt` — RFC1918-case (+1 assertie)
- `ArticleFetcherSsrfTest.kt` — RFC1918, niet-http-schema en link-local (+3 asserties)
- `docs/factory/technical-spec.md` regel 105 — de onjuiste bijzin ("de loopback- en RFC1918-cases deden het al") is vervangen door de narekenbare stand **14 van 14** met de uitsplitsing 3/4/3/4 per bestand.

**Keuzes**

- **Geen enkele regel productiecode gewijzigd.** Alle vier fetchers zetten al `errorMessage = "geblokkeerd: ..."` op elk geweigerd pad, dus de vijf nieuwe asserties slaagden meteen. `git diff` bevestigt: niets onder `src/main`.
- Elke assertie heeft exact de vorm die in datzelfde bestand al bij de loopback-case stond; geen nieuwe imports.
- Bij de drie `ArticleFetcherSsrfTest`-asserties staat één comment dat de twee motieven gescheiden houdt en expliciet zegt dat de assertie niet los "opgeruimd" mag worden: de niet-http-case bewaakt dat er wordt gevalideerd vóór het opbouwen van het `HttpRequest` (anders weigert de JDK de URL en verdwijnt onze eigen reden uit het audit-spoor); de RFC1918- en link-local-case bewaken dat de error-status van onze validator komt en niet van een DNS- of netwerkfout. Het woord uit de assertie staat bewust niet in dat comment, zodat de telling per bestand blijft kloppen.

**Wat is getest**

- `mvn -B --no-transfer-progress clean test`: BUILD SUCCESS, `Tests run: 142, Failures: 0, Errors: 0, Skipped: 0` — het aantal tests blijft gelijk, er komen alleen asserties bij.
- Telling per bestand: 3 / 4 / 3 / 4 = 14, exact één per testcase.
- Ruisfilter op de testlog (`warning|deprecat|self-attach`): 0. De 26 `WARN`-regels zijn de legitieme SSRF-afwijzingslogging.
- Niet-vacuïteit aangetoond: bij een lege `errorMessage` valt de assertie naar `false` en faalt de test — ze kunnen niet stil groen staan. De developer heeft daarnaast de validatievolgorde omgedraaid in een kopie en zag `blocks article fetch for non-http scheme` daadwerkelijk falen.
- Review en teststap: beiden akkoord, geen bevindingen. Het groene bewijs is aan de exacte revisie gekoppeld via de tree-sha.

**Bewust niet gedaan**

- Geen wijziging aan `SsrfUrlValidator` of `SsrfUrlValidatorTest` (die testen de validator zelf en kennen het woord "geblokkeerd" per definitie niet).
- `PodcastAudioDownloaderSsrfTest` was al 4/4 en is ongemoeid gelaten.
- Geen UI-screenshots: de diff raakt uitsluitend testcode en één docregel, er is geen zichtbaar of runtimegedrag veranderd. De preview draait wel (HTTP 200 op de root).

```json
```
