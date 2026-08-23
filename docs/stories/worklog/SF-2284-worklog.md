# SF-2284 - Worklog

Story-context bij eerste pickup:
Vijf ontbrekende "geblokkeerd"-asserties toevoegen en de spec-zin corrigeren

Puur testwerk plus één doczin; GEEN enkele wijziging onder src/main.

1. newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/rss/RssFetcherSsrfTest.kt, test `blocks fetch for private rfc1918 host`: voeg toe `assertTrue(loggedCall?.errorMessage?.contains("geblokkeerd") ?: false)` - exact de vorm die in dezelfde klasse al op de loopback-case staat.
2. .../podcast_source/PodcastFeedFetcherSsrfTest.kt, test `blocks fetch for private rfc1918 host`: voeg toe `assertTrue(result.errorMessage?.contains("geblokkeerd") ?: false)` - vorm gelijk aan de bestaande case in dat bestand.
3. .../rss/ArticleFetcherSsrfTest.kt, drie tests (`blocks article fetch for private rfc1918 host`, `blocks article fetch for non-http scheme`, `blocks article fetch for link-local cloud metadata endpoint`): voeg per test `assertTrue(loggedCalls.single().errorMessage?.contains("geblokkeerd") ?: false)` toe, vorm gelijk aan de bestaande loopback-case. Zet er één kort comment bij dat vastlegt wat ze bewaken en dat ze niet los 'opgeruimd' mogen worden: de niet-http-assertie bewaakt dat ArticleFetcher valideert VOOR het bouwen van het HttpRequest (draait iemand die volgorde om, dan komt de JDK-melding 'invalid URI scheme' in external_calls in plaats van onze eigen reden); de rfc1918- en link-local-assertie bewaken dat de error-status van SsrfUrlValidator komt en niet van een netwerk- of DNS-fout. Haal die twee motieven niet door elkaar.
4. docs/factory/technical-spec.md: corrigeer in de bullet 'Asserteer in de tests op de reden van de afwijzing...' de onjuiste bijzin. LET OP: die zin staat op REGEL 105, niet op :110 zoals de oorspronkelijke storytekst zegt; ga op de zinstekst af, nooit op het regelnummer. Schrijf op dat SF-2249 de niet-http-cases van twee fetchers aanvulde en dat deze story de resterende vijf cases heeft gelijkgetrokken, zodat de regel nu repo-breed sluit; noem narekenbaar 14 van 14 (3/4/3/4 per bestand). Er mag geen zin meer staan die claimt dat de loopback- en RFC1918-cases de assertie 'al deden'.

Geen nieuwe imports nodig: alle drie de testbestanden importeren assertTrue al. Het aantal tests blijft 142; er komen alleen asserties bij.

Afronden met een eigen review-stap en `mvn -B --no-transfer-progress clean test` vanuit newsfeedbackend/newsfeedbackend (BUILD SUCCESS, Tests run: 142, Failures: 0, Errors: 0). Controleer zelf `grep -c 'geblokkeerd'` = 3/4/3/4 en `git diff --stat` (alleen drie testbestanden, technical-spec.md en het worklog).

Buiten scope: SsrfUrlValidator, SsrfUrlValidatorTest, PodcastAudioDownloaderSsrfTest (al 4/4) en elke wijziging onder src/main.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-2285 (developer) uitgevoerd: vijf ontbrekende `contains("geblokkeerd")`-asserties toegevoegd
  (`RssFetcherSsrfTest` rfc1918, `PodcastFeedFetcherSsrfTest` rfc1918, `ArticleFetcherSsrfTest`
  rfc1918 + niet-http-schema + link-local). Vorm per bestand gelijk aan de bestaande loopback-case;
  geen nieuwe imports nodig, geen enkele wijziging onder `src/main`.
- Bij de drie `ArticleFetcherSsrfTest`-asserties staat één comment dat de twee motieven gescheiden
  houdt: de niet-http-case bewaakt de validatievolgorde (validatie vóór `HttpRequest.Builder.uri(...)`,
  anders komt de JDK-melding "invalid URI scheme" in `external_calls` in plaats van onze eigen reden);
  de rfc1918- en link-local-case bewaken dat de `error`-status van `SsrfUrlValidator` komt en niet van
  een netwerk- of DNS-fout. Het comment zegt expliciet dat de assertie niet los opgeruimd mag worden.
  Let op bij toekomstige edits: het woord uit de assertie mag níét in dat comment staan, anders klopt
  de telling van AC2 (`grep -c` per bestand) niet meer.
- `docs/factory/technical-spec.md` (bullet "Asserteer in de tests op de reden van de afwijzing…"):
  de onjuiste bijzin "de loopback- en RFC1918-cases deden het al" is weg. Er staat nu dat SF-2249
  alleen de niet-http-cases van twee fetchers aanvulde (9 van 14) en dat SF-2285 de resterende vijf
  gelijktrok, met de narekenbare stand 14 van 14 (3/4/3/4 per bestand).

Verificatie (agentcontainer, 2026-08-23):
- `mvn -B --no-transfer-progress clean test` vanuit `newsfeedbackend/newsfeedbackend`:
  exit 0, BUILD SUCCESS, `Tests run: 142, Failures: 0, Errors: 0, Skipped: 0`, log 331 regels
  (identiek aan de SF-2270-baseline, dus geen extra logruis).
- Ruisfilter `grep -icE 'warning|deprecat|self-attach'` op die log: 0.
- `grep -c 'geblokkeerd'`: `RssFetcherSsrfTest` 3, `ArticleFetcherSsrfTest` 4,
  `PodcastFeedFetcherSsrfTest` 3, `PodcastAudioDownloaderSsrfTest` 4 — samen 14.
- `git diff --stat`: uitsluitend de drie testbestanden + `docs/factory/technical-spec.md`
  (15 insertions, 1 deletion); niets onder `src/main`.

Review SF-2285 (2026-08-23) — akkoord, geen bevindingen:
- Revisie-anker hard: `git rev-parse HEAD^{tree}` = `632c1418…` = `testedTreeSha` uit
  [FACTORY VERIFICATION EVIDENCE] (`backend-maven-verify` passed, exit 0). Het groene bewijs
  hoort dus bij precies deze boom.
- AC1: `target/surefire-reports` uit die run sommeert tot 142 tests; `grep -hL "Failures: 0,
  Errors: 0" target/*-reports/*.txt` is leeg (alles groen).
- AC2 zelf nagemeten: `grep -c 'geblokkeerd'` = 3/4/3/4 = 14, en per bestand exact gelijk aan
  het aantal `@Test`-methodes (3/4/3/4) — dus één assertie per testcase, geen dubbeltelling in
  het nieuwe comment.
- AC4: `git diff main...HEAD --name-only | grep src/main` is leeg; alleen de drie testbestanden,
  `technical-spec.md` en dit worklog.
- AC5: `technical-spec.md:105` bevat geen "deden het al"-claim meer en noemt narekenbaar
  **14 van 14** met de 3/4/3/4-uitsplitsing.
- Vorm van de vijf nieuwe asserties is identiek aan de bestaande loopback-case in hetzelfde
  bestand; geen nieuwe imports, geen productiecode geraakt.

Test SF-2286 (2026-08-23) — akkoord, geen bevindingen:
- AC1: `mvn -B --no-transfer-progress clean test` vanuit `newsfeedbackend/newsfeedbackend`:
  exit 0, BUILD SUCCESS, `Tests run: 142, Failures: 0, Errors: 0, Skipped: 0`.
  Testlog 332 regels (worklog noteerde 331; 1 regel verschil is een tijd-/volgorderegel,
  geen extra ruis — zie AC3).
- AC2: `grep -c 'geblokkeerd'` = `RssFetcherSsrfTest` 3, `ArticleFetcherSsrfTest` 4,
  `PodcastFeedFetcherSsrfTest` 3, `PodcastAudioDownloaderSsrfTest` 4 — samen 14 van 14.
- AC3: ruisfilter `grep -icE 'warning|deprecat|self-attach'` op de testlog = 0.
  (De 26 `WARN `-regels zijn de legitieme SSRF-afwijzingslogging; daar is bewust niet op gegrept.)
- AC4: `git diff --name-only main...HEAD | grep src/main` is leeg; alleen de drie testbestanden,
  `docs/factory/technical-spec.md` en dit worklog. Productiepad `ArticleFetcher.kt:53` zet nog
  steeds `errorMessage = "geblokkeerd: ${validation.reason}"` — ongewijzigd.
- AC5: `technical-spec.md:105` bevat geen "deden het al"-claim meer en noemt expliciet 14 van 14.
- Niet-vacuïteit van de vijf nieuwe asserties: bij een `null` errorMessage valt
  `?: false` naar `false` en faalt de test — ze kunnen dus niet stil groen staan.
- Preview-smoke `https://pnf-pr-242.vdzonsoftware.nl/`: HTTP 200 (root laadt);
  `/api/actuator/health` geeft 403 (auth-guarded, verwacht). Geen UI-screenshots gemaakt:
  de diff raakt uitsluitend testcode en één docregel, er is geen frontend- of runtimegedrag
  gewijzigd om visueel te bewijzen.
