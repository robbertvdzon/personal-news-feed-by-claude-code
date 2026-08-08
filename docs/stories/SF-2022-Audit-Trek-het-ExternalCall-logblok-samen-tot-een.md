# SF-2022 - [Audit] Trek het ExternalCall-logblok samen tot één helper op ExternalCallLogger

## Story

[Audit] Trek het ExternalCall-logblok samen tot één helper op ExternalCallLogger

<!-- refined-by-factory -->

## Samenvatting

Op acht plekken in de backend staat exact hetzelfde stukje logica om een externe API-aanroep vast te leggen: telkens opnieuw een uniek nummer, een eindtijd, een doorlooptijd en een vangnet dat voorkomt dat een mislukte logregel de rest van het werk stukmaakt. Samen is dat zo'n 260 regels bijna-identieke code — het grootste kopieerblok in de codebase.

Deze story trekt dat samen tot één gedeelde hulpmethode. De acht aanroepers geven alleen nog door wat écht per plek verschilt (welke leverancier, welke actie, hoeveel eenheden, wat het kostte). Er verandert niets aan wat er in de database terechtkomt en niets aan wat gebruikers merken; het is puur opruimwerk dat toekomstige wijzigingen op één plek mogelijk maakt. Daarnaast wordt de bijbehorende documentatie in de code gecorrigeerd: die belooft nu gedrag en een opslaglocatie die niet meer kloppen.

## Scope

**In scope**

1. **Nieuwe helper op `external_call/ExternalCallLogger.kt`.** Voeg een methode toe met de vorm:

   `logCall(provider, action, username, started, unitType, status, units = null, costUsd = 0.0, errorMessage = null, subject = null, tokensIn = null, tokensOut = null)`

   Deze helper vult zelf `id` (random UUID), `endTime` (`Instant.now()`) en `durationMs` (verschil met `started`) in, bouwt de `ExternalCall` en delegeert naar het bestaande `log(call)`. Het geheel zit in een `try/catch (Exception)` die de fout inslikt en één `log.warn("... could not log external_call: {}")` doet.

2. **Helper is een default-implementatie op de interface, niet op `ExternalCallLoggerImpl`.** `log(call)` blijft het enige abstracte contractlid. Reden: de vijf bestaande testklassen bouwen een anonieme `object : ExternalCallLogger` die alleen `log(call)` overschrijft en vervolgens asserteert op de opgevangen `ExternalCall` (status, units, errorMessage, subject). Met een default-implementatie blijven die vijf klassen letterlijk ongewijzigd én blijft hun dekking op de veldwaarden intact; met een abstracte methode zouden alle vijf herschreven moeten worden en die dekking verliezen. `ExternalCallLoggerImpl` blijft dus zoals hij is.

3. **Vervang de acht privé-logfuncties door een aanroep van de helper:**
   - `ai/infrastructure/OpenAiChatHttpClient.kt` (`logCall`, r158-194) — enige aanroeper die `tokensIn`/`tokensOut` vult
   - `podcast/infrastructure/TtsClient.kt` (`logTts`, r189-222)
   - `search/TavilyClient.kt` (`logCall`, r118-149)
   - `ai/WhisperClient.kt` (`logCall`, r182-213)
   - `podcast_source/infrastructure/PodcastAudioDownloader.kt` (`log`, r81-112)
   - `rss/infrastructure/RssFetcher.kt` (`logFetch`, r104-134)
   - `rss/infrastructure/ArticleFetcher.kt` (`logFetch`, r85-115)
   - `podcast_source/infrastructure/PodcastFeedFetcher.kt` (`logFetch`, r149-179)

   De aanroepende code op de plaats van de fetch/call blijft ongewijzigd: de bestaande `try/finally`- respectievelijk `.also`-structuren en de berekening van `status`, `errorMessage` en `units` blijven staan.

4. **Imports opruimen — selectief.** `import com.vdzon.newsfeedbackend.external_call.ExternalCall` kan uit alle acht bestanden verdwijnen zodra er geen `ExternalCall.PROVIDER_*`/`ACTION_*`/`UNIT_*`-constant meer in het bestand gebruikt wordt; die constanten blijven op veel plekken wél nodig, dus per bestand controleren. `import java.util.UUID` mag alléén weg waar hij daadwerkelijk ongebruikt raakt — **niet** in `RssFetcher.kt` (r81, id van een `RssItem`) en **niet** in `WhisperClient.kt` (r94, multipart-boundary).

5. **KDoc-correcties.** Verwijder de onjuiste zin over "één regel naar `data/external_calls.jsonl`" op drie plekken en vervang die door een verwijzing naar de Postgres-tabel `external_calls`:
   - `external_call/ExternalCallLogger.kt:6`
   - `external_call/ExternalCall.kt:7`
   - `rss/infrastructure/RssFetcher.kt:40` (dezelfde stale bewering; staat niet in de oorspronkelijke storytekst maar het bestand wordt toch al aangeraakt)

   Werk in `ExternalCallLogger.kt` tegelijk de belofte "faalt non-fataal" bij, die na deze wijziging pas echt door de interface zelf wordt waargemaakt, en documenteer de nieuwe helper kort.

**Expliciet buiten scope**

- `specs/backend-functional-spec.md:55` en `:514` bevatten dezelfde stale `external_calls.jsonl`-bewering (r514 koppelt hem bovendien aan `app.data-dir`). Dat is bestaande docs-drift buiten de code en hoort in een aparte story.
- Geen wijziging aan `ExternalCallRepository`, `ExternalCallQuery`, `Pricing`, het databaseschema of enige migratie.
- Geen wijziging aan frontend of API-contract (`specs/openapi.yaml`): deze story raakt geen enkel endpoint.

## Acceptance criteria

1. `external_call/ExternalCallLogger.kt` bevat naast `log(call)` de nieuwe `logCall(...)`-methode als default-implementatie, die `id`/`endTime`/`durationMs` zelf invult en de exception zelf afvangt.
2. In alle acht genoemde bestanden is de privé-logfunctie weg en vervangen door één aanroep van `logCall(...)`.
3. Buiten het package `external_call` construeert geen enkel bestand in `src/main` nog `ExternalCall(` — te controleren met één grep. (Nulmeting nu: 8 treffers buiten `external_call/`.)
4. Geen gedragswijziging in de geschreven `external_calls`-rijen. Concreet per aanroeper ongewijzigd: provider, action, unitType, units, costUsd, status, errorMessage, tokensIn/tokensOut (alleen OpenAI), en de subject-waarde inclusief de bestaande `.take(120)`-afkapping en de samengestelde string `"guid=${guid.take(60)} url=${url.take(60)}"` in `PodcastAudioDownloader`. De helper past zelf **geen** afkapping toe (zie Aannames).
5. De vijf testklassen die een fake `callLogger` bouwen (`RssFetcherSsrfTest`, `RssFetcherImageUrlTest`, `ArticleFetcherSsrfTest`, `PodcastFeedFetcherSsrfTest`, `PodcastAudioDownloaderSsrfTest`) compileren en slagen **ongewijzigd**.
6. `mvn -B clean verify` is groen en de build blijft volledig waarschuwingsvrij (alleen de vier bekende JVM/ByteBuddy-regels over dynamic agent loading).
7. De drie KDoc-plekken uit Scope-punt 5 noemen geen `external_calls.jsonl` meer, en `ExternalCallLogger` documenteert dat het non-fatale gedrag nu in de interface zelf zit.
8. De worklog vermeldt expliciet de uniformering van de log-prefixes (zie Aannames) en de niet-meegenomen docs-drift in `specs/backend-functional-spec.md`.

## Aannames

1. **De helper kapt `subject` niet zelf af.** Zeven aanroepers doen nu `subject.take(120)`; `PodcastAudioDownloader` doet dat bewust níet en levert een string van maximaal ~130 tekens (`"guid=" + 60 + " url=" + 60`). Zou de helper centraal `.take(120)` toepassen, dan zouden díe rijen inhoudelijk veranderen — in strijd met "geen gedragswijziging". De afkapping blijft dus bij de aanroepers; `subject` gaat als kant-en-klare waarde de helper in.
2. **De acht log-prefixes worden uniform.** `[RSS]`, `[Tavily]`, `[Whisper]`, `[OpenAI-chat]`, `[TTS]`, `[ArticleFetcher]`, `[PodcastFeed]` en `[PodcastAudio]` gaan op in één prefix (bv. `[ExternalCallLog]`) in de `log.warn` van de helper. Dat is aanvaard: het betreft een foutpad dat in de praktijk niet optreedt. Wordt in de worklog genoemd.
3. **De AC-zin over "e2e-klassen die op de `external_calls`-tabel asserteren" berust op een misvatting.** Die tests bestaan niet: de e2e-klassen gebruiken uitsluitend de `ExternalCall.ACTION_*`-constanten om `FakeOpenAiChatClient`-aanroepen te tellen en te scripten, en construeren of lezen nergens een `ExternalCall`-rij. Er is dus geen e2e-aanpassing te verwachten; blijkt er toch een te breken, dan is dat een signaal van echte gedragsverandering en niet van testonderhoud.
4. **De parameters `units` en `costUsd` behouden hun huidige semantiek per aanroeper**, inclusief de gevallen die vandaag een vaste waarde doorgeven (`units = 1` bij Tavily, `costUsd = 0.0` bij RSS/web/podcast-download). Defaults op de helper (`units = null`, `costUsd = 0.0`) zijn gemak, geen nieuw gedrag: waar vandaag expliciet een waarde staat, blijft die expliciet.
5. **Deze story is geen duplicaat.** `git log origin/main` bevat geen eerdere story met dezelfde scope; de acht duplicaatblokken staan onverkort in de huidige `main`.

## Eindsamenvatting

Ik heb `.task.md`, het worklog, de story-diff en de nieuwe helper gelezen. Hieronder de eindsamenvatting.

---

# SF-2022 — ExternalCall-logblok samentrekken tot één helper

## Wat is gebouwd

Acht bijna-identieke privé-logfuncties in de backend zijn samengetrokken tot één gedeelde methode `logCall(...)` op `external_call/ExternalCallLogger.kt`. Die helper vult zelf het unieke id, de eindtijd en de doorlooptijd in, bouwt de `ExternalCall` en vangt zelf elke fout af, zodat een mislukte logregel de business-flow nooit stukmaakt.

De acht aanroepers (`OpenAiChatHttpClient`, `TtsClient`, `TavilyClient`, `WhisperClient`, `PodcastAudioDownloader`, `RssFetcher`, `ArticleFetcher`, `PodcastFeedFetcher`) geven nu alleen nog door wat per plek écht verschilt: leverancier, actie, aantal eenheden, kosten, status en subject. Netto ruim 100 regels duplicatie weg (12 bestanden, +433/−322 inclusief nieuwe test en worklog).

Daarnaast is verouderde KDoc gecorrigeerd op drie plekken (`ExternalCallLogger.kt`, `ExternalCall.kt`, `RssFetcher.kt`): de belofte dat er "één regel naar `data/external_calls.jsonl`" wordt geschreven klopte niet meer en verwijst nu naar de Postgres-tabel `external_calls`.

## Belangrijkste keuzes

- **Helper als default-implementatie op de interface**, niet op `ExternalCallLoggerImpl`. Hierdoor blijven de vijf bestaande testfakes (anonieme `object : ExternalCallLogger` die alleen `log(call)` overschrijven) letterlijk ongewijzigd én behouden ze hun dekking op de veldwaarden. `log(call)` blijft het enige verplichte contractlid.
- **De helper kapt `subject` bewust niet af.** De bestaande `.take(120)` blijft bij de zeven aanroepers staan; `PodcastAudioDownloader` houdt zijn ongekapte samengestelde string. Centraal afkappen zou de inhoud van díe rijen veranderd hebben.
- **Expliciete waarden blijven expliciet** (`units = 1` bij Tavily, `costUsd = 0.0` bij RSS/web/podcast-download); de defaults op de helper zijn gemak, geen nieuw gedrag.
- **Imports selectief opgeruimd**: `java.util.UUID` is bewust behouden in `RssFetcher` (id van een `RssItem`) en `WhisperClient` (multipart-boundary); de `ExternalCall`-import blijft overal staan voor de `PROVIDER_*`/`ACTION_*`/`UNIT_*`-constanten.
- **Eén bewust aanvaarde gedragsverandering**: de acht foutprefixes (`[RSS]`, `[Tavily]`, `[Whisper]`, …) zijn in de catch-tak van het loggen opgegaan in één prefix `[ExternalCallLog]`. Dit raakt uitsluitend het foutpad "loggen zelf mislukt", dat in de praktijk niet optreedt. Alle overige logregels houden hun eigen prefix.

## Wat is getest

- `mvn -B clean verify` (twee volledige runs): BUILD SUCCESS, 110 unit-tests (was 106) en 61 e2e-tests, 0 failures / 0 errors. Build volledig waarschuwingsvrij.
- Nieuwe unittest `ExternalCallLoggerTest` (4 tests) op het nu gedeelde gedrag: id/endTime/durationMs worden ingevuld, defaults blijven leeg, `subject` wordt niet afgekapt, en een falende `log(call)` wordt ingeslikt.
- De vijf bestaande testfakes zijn ongewijzigd en groen — het bewijs dat de interfacekeuze klopt.
- Acceptatiecriterium nageteld met grep: buiten het package `external_call` construeert geen enkel bestand in `src/main` nog een `ExternalCall(` (nulmeting was 8 treffers). `external_calls.jsonl` komt nergens meer voor in `src/main`.
- Reviewer heeft alle acht call-sites veld-voor-veld vergeleken met de oude code: provider, action, unitType, units, costUsd, status, errorMessage, tokensIn/tokensOut en subject zijn identiek gebleven. Geen gedragswijziging in de geschreven rijen.

## Bewust niet gedaan

- **Docs-drift in `specs/backend-functional-spec.md:55` en `:514`**: daar staat dezelfde verouderde `external_calls.jsonl`-bewering (r514 koppelt hem bovendien aan `app.data-dir`). Stond expliciet buiten scope; **advies: hiervoor een aparte docs-story aanmaken.**
- Geen wijziging aan `ExternalCallRepository`, `ExternalCallQuery`, `Pricing`, het databaseschema of migraties.
- Geen wijziging aan frontend of API-contract (`specs/openapi.yaml`) — deze story raakt geen enkel endpoint.
- De aanname in de oorspronkelijke acceptatiecriteria over "e2e-klassen die op de `external_calls`-tabel asserteren" bleek onjuist: die tests bestaan niet, de e2e-klassen gebruiken alleen de `ACTION_*`-constanten. Er was dus geen e2e-aanpassing nodig.

## Aandachtspunt

Het worklog bevat geen apart tester-blok van subtaak SF-2024; het testbewijs komt uit de developer- en reviewer-runs (twee volledige `clean verify`-runs plus een gerichte hertest van de zes relevante testklassen). Inhoudelijk is de dekking daarmee aantoonbaar, maar de tester-stap heeft geen eigen vastlegging achtergelaten.

<!-- deploy-summary:start -->
Er is opruimwerk gedaan achter de schermen: het bijhouden van externe aanroepen gebeurt nu op één centrale plek in plaats van acht keer apart. Voor jou verandert er niets zichtbaars — de app werkt precies hetzelfde. Toekomstige aanpassingen aan deze registratie zijn hierdoor sneller en met minder risico door te voeren.
<!-- deploy-summary:end -->
