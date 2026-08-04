# SF-1877 - [Audit] SSRF-validatie toepassen op de podcast-audio-URL (PodcastAudioDownloader)

## Story

[Audit] SSRF-validatie toepassen op de podcast-audio-URL (PodcastAudioDownloader)

<!-- refined-by-factory -->

## Samenvatting

Onze server haalt podcast-audio op via een link die uit de podcast-feed zelf komt. Bij RSS-artikelen controleren we zo'n link al voordat we hem ophalen; bij podcast-audio nog niet. Daardoor kan de eigenaar van een podcast-feed onze server in principe naar een intern adres laten bellen.

Deze story sluit dat laatste gaatje: vlak voor het downloaden van de audio wordt de link net zo gecontroleerd als bij artikelen. Wordt hij afgekeurd, dan gaat er geen verzoek uit en mislukt de download netjes — precies zoals nu al gebeurt bij een dode downloadlink. Voor gewone, geldige podcasts verandert er niets.

## Scope

**Code (`newsfeedbackend`):**
- `podcast_source/infrastructure/PodcastAudioDownloader.kt`:
  - Constructor-property toevoegen conform de bestaande conventie (identiek aan `ArticleFetcher.kt:31`):
    `@param:Value("\${app.security.ssrf.allow-loopback:false}") private val ssrfAllowLoopback: Boolean = false`
  - In `download()` de `audioUrl` valideren via `common.SsrfUrlValidator.validate(audioUrl, allowLoopback = ssrfAllowLoopback)` vlak vóór het opbouwen/versturen van het request (huidige regels 40-44), dus mét verse DNS-resolutie op fetch-moment.
  - Bij `ValidationResult.Invalid`: `log.warn`, `status = "error"`, `errorMessage = "geblokkeerd: ${validation.reason}"`, temp-file opruimen (`tempFile.delete()`), `return null`. Het bestaande `finally`-blok logt dan automatisch één `ExternalCall`; `size` blijft `0L`.

**Test (`src/test/kotlin/.../podcast_source/PodcastAudioDownloaderSsrfTest.kt`, nieuw):**
- Gespiegeld op `rss/ArticleFetcherSsrfTest.kt` (plain JUnit5, anonieme `object : ExternalCallLogger`, geen Spring/Mockito), geplaatst naast `podcast_source/PodcastFeedFetcherSsrfTest.kt`.
- Vier gevallen: loopback (`http://127.0.0.1:1/ep.mp3`), RFC1918 (`http://10.0.0.5/ep.mp3`), niet-http-scheme (`file:///etc/passwd`), link-local metadata (`http://169.254.169.254/latest/meta-data/`).

**Documentatie:**
- `specs/backend-functional-spec.md` §6.4 ("SSRF-hardening (SF-1387)", ~r269-282): de audio-/enclosure-fetch als derde validatiemoment toevoegen naast opslaan en feed-fetch.
- `specs/backend-functional-spec.md` §7.5 slotalinea: `PodcastAudioDownloader` toevoegen aan de opsomming van fetchers met `Redirect.ALWAYS` waarvoor redirect-SSRF een bekend restrisico blijft.
- `specs/backend-technical-spec.md` (lijst SSRF-testklassen, r296-305): de nieuwe testklasse als regel toevoegen, in dezelfde stijl als de `ArticleFetcherSsrfTest`-regel.

**Buiten scope:** `docs/factory/technical-spec.md` (regel is al generiek), `PodcastFeedFetcher`, `PodcastIngestionPipeline`, `PodcastTranscriptProcessor`, gedrag van `Redirect.ALWAYS`, en validatie van de enclosure-URL bij opslag in `podcast_episodes`.

## Acceptance criteria

1. Bij een `audioUrl` die door `SsrfUrlValidator` wordt afgewezen (niet-http/https-scheme, of een host die resolvet naar loopback/link-local/private/multicast) gaat er **geen** HTTP-request uit en retourneert `download()` `null`.
2. Bij zo'n afwijzing wordt precies één `ExternalCall` gelogd met `action=podcast_audio_download`, `status="error"`, `units=0` en een `errorMessage` die de tekst `geblokkeerd` bevat.
3. Bij afwijzing blijft er geen temp-file achter: de in `download()` aangemaakte temp-file wordt verwijderd.
4. Voor geldige audio-URLs is het gedrag ongewijzigd: dezelfde download, dezelfde `ExternalCall` met `status="ok"` en `units` = aantal bytes.
5. `app.security.ssrf.allow-loopback=true` (alleen gezet in de e2e-omgeving) laat loopback-URLs toe, zodat de bestaande e2e-tests met de fake contentserver op `127.0.0.1` onveranderd blijven slagen.
6. De nieuwe testklasse dekt de vier genoemde gevallen, elk met assertie op `null` + één `ExternalCall` met `status="error"`.
7. De caller `PodcastTranscriptProcessor` is niet gewijzigd; een geblokkeerde URL loopt via het bestaande `audioFile == null`-pad (status `SHOW_NOTES_DONE` + `errorMessage` "Audio-download faalde"), er ontstaat geen nieuw foutpad.
8. `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend` slaagt, zonder nieuwe compile-warnings.
9. De drie genoemde docs-plekken beschrijven na de wijziging drie validatiemomenten voor de podcast-tak en noemen de nieuwe testklasse.

## Aannames

- De validatie komt binnen de bestaande `try`-block te staan. `PodcastAudioDownloader` logt via `try/finally` (niet via `.also` zoals `ArticleFetcher`), dus een `return null` in de validatie-tak logt de `ExternalCall` gewoon — de valkuil uit SF-1843 speelt hier niet.
- De temp-file wordt in de huidige code al vóór de `try` aangemaakt (r38); daarom is expliciet `tempFile.delete()` in de afwijzings-tak nodig, analoog aan de bestaande `>= 400`- en `catch`-takken.
- Het `subject`-veld van de `ExternalCall` blijft de bestaande vorm `guid=… url=…` houden; de assertie `assertEquals(url, call.subject)` uit `ArticleFetcherSsrfTest` wordt in de nieuwe test dus aangepast (bijv. `assertTrue(call.subject.contains(url))`) in plaats van 1-op-1 overgenomen.
- Alleen `PodcastAudioDownloader.download()` wijzigt; er komt geen validatie bij in de ingestie-pipeline of bij het opslaan van episodes, omdat het afgesproken validatiemoment "vlak vóór de fetch" is.
- De story noemt §7.5 als plek van de podcast-beschrijving; feitelijk staan de twee bestaande podcast-validatiemomenten in §6.4 en verwijst §7.5 er alleen naar. Beide secties worden daarom bijgewerkt zoals in Scope beschreven.
- De lijst met SSRF-testklassen in `specs/backend-technical-spec.md` wordt meegenomen (niet genoemd in de story, maar deze lijst wordt bij elke SSRF-story bijgehouden — SF-1843 deed dat ook).

## Eindsamenvatting

Alles gelezen: `.task.md`, het worklog (SF-1878 developer + SF-1879 tester) en de volledige story-diff.

## SF-1877 — SSRF-validatie op de podcast-audio-URL (PodcastAudioDownloader)

### Wat is gebouwd
De backend downloadt podcast-audio via een link (enclosure-URL) die uit de podcast-feed zelf komt. Die link werd nergens gecontroleerd, terwijl dat bij de feed-URL en sinds SF-1843 ook bij de artikel-URL al wél gebeurt. De eigenaar van een feed kon onze server daarmee in principe naar een intern adres laten verbinden. Deze story sluit dat laatste gat in de SSRF-hardening (de vervolgstory die SF-1843 al aankondigde).

Gewijzigd — 4 bestanden + worklog, uitsluitend de `podcast_source`-module:
- `podcast_source/infrastructure/PodcastAudioDownloader.kt` — constructor-property `ssrfAllowLoopback` volgens exact het bestaande patroon van `ArticleFetcher` (geen nieuwe config-property), plus `SsrfUrlValidator.validate(...)` als eerste statement in de bestaande `try`, dus met verse DNS-resolutie vlak vóór het request.
- Nieuwe unittest `podcast_source/PodcastAudioDownloaderSsrfTest.kt` — 4 gevallen, plain JUnit5, geen Spring/Mockito.
- `specs/backend-functional-spec.md` (§6.4, §7.5, §10) en `specs/backend-technical-spec.md` (testklassenlijst) bijgewerkt.

### Gemaakte keuzes
- **Afwijzing = "audio-download faalde", geen nieuw foutpad.** Bij een geblokkeerde URL: WARN-log, `status="error"`, `errorMessage="geblokkeerd: <reden>"`, temp-file opgeruimd, `download()` geeft `null`. `PodcastTranscriptProcessor` is niet aangeraakt en loopt via het bestaande `audioFile == null`-pad (status `SHOW_NOTES_DONE`).
- **Expliciete `tempFile.delete()`** in de afwijzings-tak, omdat de temp-file al vóór de `try` wordt aangemaakt — net als in de bestaande `>= 400`- en `catch`-takken.
- **De SF-1843-valkuil speelt hier niet:** deze klasse logt via `try/finally` (niet via `.also`), dus een vroege `return null` logt de `ExternalCall` gewoon. Bewust geverifieerd in plaats van het SF-1843-patroon blind te kopiëren.
- **Testsjabloon aangepast waar het moest:** `subject` heeft hier de vorm `guid=… url=…` en is nullable, dus geen 1-op-1 `assertEquals(url, subject)` maar een `contains`-assertie.
- **Eén extra docs-bullet** in §10 Foutafhandeling buiten de gevraagde drie plekken, zodat het nieuwe blokkeerpad niet als enige ontbreekt in die opsomming.

### Wat is getest
- `mvn -B clean verify` (developer én tester, beide BUILD SUCCESS, exit 0): **102 unit-tests** (was 98) + **61 e2e-tests**, 0 failures / 0 errors.
- De 4 nieuwe SSRF-cases groen: loopback `127.0.0.1`, RFC1918 `10.0.0.5`, `file:///etc/passwd`, cloud-metadata `169.254.169.254`. Elk: `null` terug, precies één `ExternalCall` (`status="error"`, `units=0`, `errorMessage` bevat "geblokkeerd"), geen uitgaand request.
- **AC5 hard bewezen in de e2e-run:** `PodcastIngestE2eTest` logt `[PodcastAudio] http://127.0.0.1:44733/… -> 404` — het request gáát dus wél uit met `app.security.ssrf.allow-loopback=true`. De escape-hatch voor de e2e-omgeving werkt aantoonbaar.
- Geen nieuwe compile-warnings; de enige `[WARNING]`-regels zijn de pre-existing `asText()`-deprecations (bekend sinds SF-1338).
- Preview `pnf-pr-206` draaide aantoonbaar branch-HEAD (`sha-e324584`): app 200, health UP, `/api/feed` zonder token 403.
- Review van SF-1878: akkoord, alle acceptatiecriteria nagelopen, geen scope-creep.

### Bewust niet gedaan
- **Geen browser-/screenshotbewijs.** De audio-download is een interne scheduler-stap zonder API- of UI-oppervlak en de diff bevat 0 regels Dart. Het gedragsbewijs zit volledig in de unit- + e2e-run.
- **SSRF-via-redirect blijft niet afgevangen** — een server die pas ná validatie via een 3xx naar een intern adres doorstuurt, komt er nog doorheen. Bekend en gedocumenteerd restrisico; `HttpClient.Redirect.ALWAYS` is bewust ongewijzigd. `PodcastAudioDownloader` is nu wel toegevoegd aan die restrisico-opsomming in §7.5.
- **Geen validatie bij het opslaan van de enclosure-URL** in `podcast_episodes` — het afgesproken validatiemoment is "vlak vóór de fetch".
- Niet aangeraakt: `SsrfUrlValidator` zelf, `PodcastFeedFetcher`, `PodcastIngestionPipeline`, `PodcastTranscriptProcessor`, `docs/factory/technical-spec.md` (regel is al generiek), API-contract, deploy-manifests, frontend.
- **Nog open (klein, uit SF-1843 overgenomen):** de tekst in §6.4 opent nu met "elke URL waar de server in de podcast-tak zelf naartoe fetcht". Dat is voor de podcast-tak inmiddels feitelijk juist, maar het is een generieke formulering die bij een volgende fetcher opnieuw moet meebewegen — geen actie nodig in deze story.

<!-- deploy-summary:start -->
De app controleert nu ook het adres waar de audio van een podcast-aflevering vandaan komt, voordat hij die aflevering ophaalt. Wordt dat adres niet vertrouwd, dan slaat hij het downloaden over, precies zoals nu al gebeurt bij een link die het niet doet. Je merkt hier in het dagelijks gebruik niets van; het maakt de app alleen veiliger.
<!-- deploy-summary:end -->
