# SF-1753 - [Audit] Dode code opruimen: 8 symbolen zonder aanroepers verwijderen

## Story

[Audit] Dode code opruimen: 8 symbolen zonder aanroepers verwijderen

<!-- refined-by-factory -->

## Samenvatting

Bij de code-kwaliteitsaudit zijn stukjes code gevonden die nergens meer gebruikt worden: restanten van functies die ooit bedoeld waren maar nooit zijn aangesloten, en overblijfselen van eerder opgeruimde onderdelen. Ze doen niets, maar moeten wel telkens meegelezen en onderhouden worden.

Deze story haalt die dode code weg. Er verandert niets aan wat de app doet: geen enkel scherm, knop of resultaat wordt anders. Het is puur opruimwerk zodat de code kleiner en begrijpelijker wordt.

## Scope

Repo: `newsfeedbackend/newsfeedbackend`. Uitsluitend verwijderen van symbolen zonder aanroepers, inclusief de imports en KDoc die daardoor los komen te staan.

1. **`ExternalCallLogger.timed()` + `TimedContext`**
   - `external_call/ExternalCallLogger.kt`: `fun timed(...)` (r18-25) en `class TimedContext` (r31-39) verwijderen; de dan ongebruikte `import java.time.Instant` (r3) mee.
   - `external_call/infrastructure/ExternalCallLoggerImpl.kt`: `override fun timed(...)` (r18-35) en de alleen daardoor aangeroepen `private fun recordAndAppend(...)` (r39) verwijderen; de dan ongebruikte imports `java.time.Instant` en `java.util.UUID` mee. `log()` en de `ExternalCall`-import blijven.
   - De drie test-fakes die `timed()` alleen implementeren om de interface te vullen: `rss/RssFetcherSsrfTest.kt` (r17-20), `rss/RssFetcherImageUrlTest.kt` (r17-20), `podcast_source/PodcastFeedFetcherSsrfTest.kt` (r18-21). Alleen het `override fun timed`-blok; de fakes zelf en hun `log()` blijven.
2. **`OpenAiChatClient.completeJson()`**
   - `ai/OpenAiChatClient.kt`: `fun completeJson(...)` (r43-53) + bijbehorende KDoc; de klasse-KDoc op r7 noemt `[complete]/[completeJson]` en moet naar alleen `[complete]`.
   - `ai/infrastructure/OpenAiChatHttpClient.kt`: `override fun completeJson(...)` (r91-123); de KDoc-opsomming op r27-28 die `[completeJson]` beschrijft. Daarmee wordt `doComplete`'s `responseFormat`-parameter dood: verwijder die parameter (r134), de `if (responseFormat != null)`-regel (r156) en de twee `responseFormat = null`-argumenten (r66, r87). `mapper` blijft in gebruik en blijft staan.
   - `src/test/.../e2e/FakeOpenAiChatClient.kt`: `override fun completeJson(...)` (r53-56).
3. **`PodcastEpisodeRepository.findOneReadyForTranscript(now)`** (r103-104, incl. de KAN-60-KDoc erboven) — leftover van SF-1739. Herschrijf daarbij de KDoc van `findReadyForTranscript(now, limit)` (r106-112) zodat die zelfstandig leesbaar is en niet meer naar de verwijderde methode verwijst.
4. **`PodcastEpisodeRepository.countForFeed(...)`** (r68-73).
5. **`PodcastEpisodeRepository.deleteForFeed(...)`** (r81-85).
6. **`AuthService.userExists(username)`** (`auth/AuthService.kt` r9) + de override in `auth/domain/AuthServiceImpl.kt` r49.
7. **Ongebruikte import**: `podcast/infrastructure/Mp3Concatenator.kt` r7 `java.util.UUID`.

### Vervallen t.o.v. de oorspronkelijke auditlijst (geverifieerd, niets te doen)

- `EventDenylistRepository.ids(username)` en `EventDiscoveryPipeline.kt`: de hele `events`-module is al verwijderd in SF-1746 (`settings/infrastructure/` bevat nog uitsluitend `CategorySettingsRepository`, `PodcastFeedsRepository`, `RssFeedsRepository`). Dit punt is een no-op.
- De vermeende ongebruikte imports in `PodcastTranslationServiceImplTest.kt` en `PodcastFeedsServiceImplTest.kt`: elke import in beide bestanden wordt daadwerkelijk gebruikt (o.a. `\`when\``/`whenever` op de genoemde regels). Niets verwijderen.

### Buiten scope (bewust)

- `CategoryResult` / `NewsRequest.categoryResults` (`request/RequestService.kt`): staat in `specs/openapi.yaml` en wordt door `frontend/lib/models/models.dart` geparsed — contractbeslissing, geen opruiming.
- `TopicEntry.likedCount`/`starredCount` en de podcast-velden in `TopicHistory.kt`: hangen samen met de functionele bevinding rond `FeedSelector.kt:47` (counters die nooit opgehoogd worden); vergt eerst een keuze.
- Ongebruikte configsleutels in `application.properties` (`app.ai.pricing.source/.updated`, gpt-4o-mini-prijsregels): documentair, laten staan.
- Geen vervangende/gededupliceerde helper bouwen voor de tien handgebouwde ExternalCall-logblokken (zie aanname 1).

## Acceptance criteria

- De zeven punten hierboven zijn verwijderd; geen enkele compileerfout.
- `mvn clean compile` geeft nog steeds 0 warnings (dus ook geen "unused import"-warnings die door deze verwijderingen zouden ontstaan).
- `mvn test` blijft groen, met hetzelfde aantal tests als vóór de wijziging (er wordt geen enkele test verwijderd — alleen de `timed()`-overrides en de `completeJson()`-override in de fakes).
- `mvn verify` (e2e-suite, `.factory/verification.yaml` → `backend-maven-verify`) blijft groen.
- Geen wijziging aan publiek gedrag, HTTP-endpoints, `specs/openapi.yaml`, `frontend/`, `frontend-reader/` of de database (geen migratie).
- Er blijft geen KDoc achter die naar een verwijderd symbool verwijst (`OpenAiChatClient.kt`, `OpenAiChatHttpClient.kt`, `PodcastEpisodeRepository.kt`).

## Aannames

1. `timed()` wordt verwijderd, niet aangesloten: hij heeft de verkeerde faalsemantiek (de tien handgebouwde logblokken slikken een log-fout in, `timed()` gooit door). Een eventuele dedup-story bouwt later een non-fatale helper.
2. `completeJson()` is sinds SF-114 nooit gewired (bevestigd in `docs/stories/SF-113-eindsamenvatting.md:59`); Structured Outputs opnieuw introduceren is een aparte story.
3. Het meeverwijderen van imports en KDoc-regels die door deze verwijderingen ongebruikt/incorrect worden, hoort bij de opdracht — anders faalt het 0-warnings-criterium of blijft er misleidende documentatie staan.
4. Het opruimen van de dan dode `responseFormat`-parameter in `doComplete` valt binnen deze opruiming; het is gedragsneutraal omdat beide resterende callers al `null` doorgeven.
5. Alle verwijderde symbolen zijn intern (Kotlin-`public` maar geen HTTP-/API-oppervlak), dus er is geen externe consument die kan breken.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de story-docs gelezen. Hier is de eindsamenvatting.

## SF-1753 — [Audit] Dode code opruimen: symbolen zonder aanroepers verwijderen

**Wat is gebouwd**

Puur opruimwerk in `newsfeedbackend/newsfeedbackend`: zeven groepen code die nergens meer aangeroepen werden zijn verwijderd, inclusief de imports en KDoc die daardoor los kwamen te staan. Er is geen functionele wijziging — geen scherm, endpoint, API-contract of database is geraakt.

1. `ExternalCallLogger.timed()` + `TimedContext`, de implementatie in `ExternalCallLoggerImpl` (incl. de alleen daardoor gebruikte `recordAndAppend()`), en de drie interface-vullende `timed()`-overrides in test-fakes. `log()` blijft ongewijzigd in gebruik.
2. `OpenAiChatClient.completeJson()` + de implementatie en de fake-override. Daarmee werd ook de `responseFormat`-parameter van `doComplete` dood; die is meeverwijderd (beide overgebleven aanroepers gaven al `null` door, dus gedragsneutraal).
3–5. `PodcastEpisodeRepository.findOneReadyForTranscript()`, `countForFeed()` en `deleteForFeed()`.
6. `AuthService.userExists()` + de override in `AuthServiceImpl`.
7. Ongebruikte import in `Mp3Concatenator`.

Netto: 12 gewijzigde codebestanden, 192 regels verwijderd tegen 9 toegevoegd — en die 9 zijn uitsluitend herschreven KDoc.

**Keuzes**

- Vóór elke verwijdering is repo-breed gegrept; geen enkel symbool had nog een aanroeper.
- `timed()` is verwijderd in plaats van alsnog aangesloten: de faalsemantiek klopt niet met de bestaande logblokken (die slikken een logfout in, `timed()` gooide door). Een eventuele dedup-helper is bewust een aparte story.
- KDoc die naar verwijderde methodes verwees is bijgewerkt in plaats van blijven staan, zodat er geen misleidende documentatie achterblijft.

**Wat is getest**

- `mvn clean compile`: BUILD SUCCESS met **0 warnings** — dus geen nieuwe "unused import"-waarschuwingen.
- `mvn test` / `mvn verify`: groen, 94 unit-tests + 52 e2e-tests, 0 failures/errors. Het testaantal is aantoonbaar gelijk gebleven (147 testannotaties vóór én na); er is geen test verwijderd of toegevoegd.
- Live getest op preview `pnf-pr-201` (draaide de juiste revisie): registratie, login, dubbele registratie → 409, wachtwoord wijzigen en account verwijderen werken normaal (raakt het pad waar `userExists` verdween). Een podcastfeed toevoegen leverde 6 afleveringen met echte Nederlandse AI-samenvattingen, categorie-classificatie en feed-selectie — bewijs dat de OpenAI-client na het schrappen van `completeJson`/`responseFormat` gewoon werkt. RSS-instellingen en de SSRF-blokkade zijn ook nog intact. UI-smoke met screenshots: geen fouten.
- Het wegwerp-testaccount is na afloop opgeruimd.
- Review: geen blockers, geen bugs.

**Bewust niet gedaan**

- `EventDenylistRepository.ids` / `EventDiscoveryPipeline` uit de oorspronkelijke auditlijst: die hele events-module is al weg sinds SF-1746 — no-op.
- De "ongebruikte" imports in `PodcastTranslationServiceImplTest` en `PodcastFeedsServiceImplTest`: na controle blijken die wél in gebruik; niets aangeraakt.
- `CategoryResult` / `NewsRequest.categoryResults`: staat in het OpenAPI-contract en wordt door de frontend geparsed — dat is een contractbeslissing, geen opruiming.
- `TopicEntry.likedCount`/`starredCount` en de podcastvelden in `TopicHistory`: hangen samen met een functionele bevinding (tellers die nooit opgehoogd worden) en vergen eerst een inhoudelijke keuze.
- Ongebruikte configsleutels in `application.properties`: documentair, blijven staan.
- Geen vervangende helper gebouwd voor de tien handgebouwde ExternalCall-logblokken.

Alle acceptatiecriteria uit de story zijn afgevinkt.

```json
```
