# SF-1683 - [Audit] Podcast-feed-validatie achter de publieke service-interface i.p.v. PodcastFeedFetcher in de controller

## Story

[Audit] Podcast-feed-validatie achter de publieke service-interface i.p.v. PodcastFeedFetcher in de controller

<!-- refined-by-factory -->

## Samenvatting

De pagina waarop je podcast-feeds beheert werkt goed, maar de code erachter is op de
verkeerde plek georganiseerd: het webadres-eindpunt regelt zelf het controleren van
nieuwe feeds, terwijl dat werk hoort bij het podcast-onderdeel zelf. Deze story
verplaatst die logica naar de juiste plek.

Voor gebruikers verandert er niets: een nieuwe feed die niet op te halen is geeft nog
steeds dezelfde Nederlandse foutmelding, en na opslaan worden afleveringen nog steeds
meteen opgehaald.

## Scope

Backend-only refactor in module `podcast_source`, gedragsneutraal.

In scope:
1. **Nieuwe publieke interface in de moduleroot** `com.vdzon.newsfeedbackend.podcast_source`
   (bijv. `PodcastFeedsService`, naast bestaande `PodcastIngestionTrigger` /
   `PodcastEpisodeLookup`), met een implementatie in `podcast_source/domain/`.
2. **Verplaats de businesslogica** uit `podcast_source/api/PodcastFeedsController.kt:46-56`
   naar die domain-implementatie: bestaande feed-lijst ophalen, bepalen welke URLs nieuw
   zijn (niet-blanco én niet in de bestaande lijst), per nieuwe URL synchroon
   `PodcastFeedFetcher.fetch(url, username)` aanroepen en bij `!ok` afwijzen.
3. **Controller afslanken**: `PodcastFeedsController` injecteert nog uitsluitend
   `SettingsService` (voor `GET /api/podcast-feeds`) en de nieuwe publieke interface;
   de imports van `podcast_source.infrastructure.PodcastFeedFetcher` (regel 5) en het
   veld `fetcher` (regel 25) verdwijnen. De PUT-methode wordt een dunne doorgeefluik-call.
4. **Exception**: de domain-laag gooit `com.vdzon.newsfeedbackend.common.BadRequestException`
   in plaats van `ResponseStatusException` (`GlobalExceptionHandler.handleBadRequest`
   geeft exact dezelfde 400 + `{"error": …}`-body terug). De huidige
   `ResponseStatusException`-variant mag ook blijven; leidend is de responsvorm.
5. **KDoc bijwerken** in `podcast_source/PodcastIngestionTrigger.kt` regels 4-5: verwijs
   niet meer naar `SettingsController` maar naar de actuele caller
   (`PodcastFeedsController` / de nieuwe podcast-feeds-service). Werk ook de KDoc in
   `PodcastFeedsController.kt` bij waar die nog naar de verhuisde logica verwijst.

Buiten scope:
- `specs/openapi.yaml` (geen API-wijziging).
- De andere bevindingen uit `docs/kwaliteitsanalyse-backend.md` (A1 cycle rss ↔ podcast_source,
  A3 `StartupRunner`, A4 DTO-scheiding).
- Frontend (`frontend/lib/screens/rss_feeds_screen.dart` e.d.) en `frontend-reader`.
- Uitbreiden van `ModuleStructureTest` met een laag-check (Spring Modulith bewaakt
  modulegrenzen, geen laaggrenzen) — mogelijke vervolgstory.

## Acceptance criteria

1. `podcast_source/api/PodcastFeedsController.kt` bevat geen enkele import uit
   `…podcast_source.domain` of `…podcast_source.infrastructure`; grep over alle 14
   `@RestController`-klassen levert nul controllers met een `domain`/`infrastructure`-import op.
2. De controller-methode `savePodcastFeeds` bevat geen business logic meer: geen
   diff-berekening tegen de bestaande lijst, geen fetch-lus en geen 400-constructie.
   Ze delegeert naar de publieke `podcast_source`-interface uit de moduleroot.
3. De validatie-/diff-logica staat in `podcast_source/domain/`, achter een interface die
   direct in de moduleroot `com.vdzon.newsfeedbackend.podcast_source` staat.
4. Gedrag ongewijzigd voor `PUT /api/podcast-feeds`:
   - alleen URLs die nieuw zijn t.o.v. de opgeslagen lijst worden gefetcht; lege/blanco
     URLs worden overgeslagen; bestaande URLs worden niet opnieuw opgehaald;
   - bij een mislukte fetch volgt HTTP 400 met body
     `{"error":"Kon feed niet ophalen: <url> (<reden>)"}`, waarbij `<reden>` de
     `errorMessage` van de fetch is of `onbekende fout` als die ontbreekt;
   - bij succes wordt de lijst opgeslagen via `SettingsService.savePodcastFeeds`, het
     opgeslagen resultaat teruggegeven, en daarna de ingestion getriggerd via
     `PodcastIngestionTrigger`. Volgorde blijft: valideren → opslaan → triggeren.
5. `GET /api/podcast-feeds` blijft ongewijzigd via `SettingsService.getPodcastFeeds`.
6. De KDoc van `PodcastIngestionTrigger.kt` noemt `SettingsController` niet meer.
7. `PodcastIngestE2eTest.kt` is ongewijzigd en groen (zowel het happy path als de test
   `onbereikbare podcast-feed geeft 400 met Nederlandse foutmelding`).
8. `mvn test` (incl. `ModuleStructureTest`) is groen; `mvn verify` draait de e2e-tests groen.
9. `specs/openapi.yaml` is niet gewijzigd.

## Aannames

- **Vorm van de publieke interface**: de voorkeur is één methode die het hele
  PUT-gedrag omvat (valideren + opslaan + triggeren), bijvoorbeeld
  `PodcastFeedsService.savePodcastFeeds(username, settings): PodcastFeedsSettings`.
  De implementatie mag daarvoor `SettingsService` en `PodcastIngestionTrigger` gebruiken —
  een domain-service mag andere modules via hun publieke interface aanroepen (spec §4,
  laag 2). Een smallere `validate(username, settings)`-methode náást de bestaande
  trigger-aanroep in de controller is ook acceptabel, mits de controller dan zelf geen
  diff- of fetch-logica meer bevat.
- **Exact type/naam** van de nieuwe interface en implementatieklasse is vrij, mits de
  interface in de moduleroot staat en de implementatie in `domain/`.
- **`PodcastFeedFetcher` blijft ongewijzigd** (inclusief zijn SSRF-check en
  external-call-logging); alleen de aanroeper verschuift van api- naar domain-laag.
  De external-call-logging bij validatie blijft dus bestaan.
- **Geen nieuwe unit-test verplicht**; de bestaande e2e-dekking is het vangnet. Een
  unit-test op de nieuwe domain-service (patroon: `SettingsServiceImplSavePodcastFeedsTest.kt`,
  Mockito + JUnit5) is welkom maar niet blokkerend.
- **`docs/kwaliteitsanalyse-backend.md` wordt niet bijgewerkt** in deze story; dat is een
  analyse-momentopname, geen living doc.

## Eindsamenvatting

Ik heb `.task.md`, het worklog `docs/stories/worklog/SF-1683-worklog.md` en de volledige story-diff (`git diff main...HEAD`, 3 commits: developer/reviewer/tester) gelezen. Hieronder de eindsamenvatting.

---

# SF-1683 — Podcast-feed-validatie achter de publieke service-interface

## Wat is er gebouwd

Een gedragsneutrale backend-refactor in module `podcast_source`. De logica die bepaalt of een nieuwe podcast-feed ophaalbaar is, stond in het webadres-eindpunt (`PodcastFeedsController`) en is verplaatst naar de juiste laag, achter een publieke module-interface.

- **Nieuw** `podcast_source/PodcastFeedsService.kt` — publieke interface in de moduleroot met één methode `savePodcastFeeds(username, settings)`, in lijn met het bestaande patroon van `PodcastIngestionTrigger` / `PodcastEpisodeLookup`.
- **Nieuw** `podcast_source/domain/PodcastFeedsServiceImpl.kt` — bevat de verhuisde logica in exact dezelfde volgorde: **valideren → opslaan → triggeren**. Nieuwe URL's = niet-blanco en nog niet in de opgeslagen lijst; die worden één keer synchroon opgehaald.
- **Afgeslankt** `podcast_source/api/PodcastFeedsController.kt` — injecteert nog alleen `SettingsService` (voor de GET) en de nieuwe interface; de PUT is een dunne doorgeefluik-call. De imports naar `infrastructure.PodcastFeedFetcher` en `PodcastIngestionTrigger` zijn verdwenen.
- **KDoc bijgewerkt** in `PodcastIngestionTrigger.kt` (verwees nog naar het niet meer bestaande `SettingsController`) en in de controller.

**Voor de gebruiker verandert er niets**: dezelfde Nederlandse foutmelding bij een onbereikbare feed, en na opslaan wordt de ingestion nog steeds meteen getriggerd.

## Gemaakte keuzes

- **Eén brede interface-methode** die het hele PUT-gedrag omvat (valideren + opslaan + triggeren), i.p.v. een smallere `validate()` — dit haalt álle businesslogica uit de controller weg.
- **`BadRequestException` i.p.v. `ResponseStatusException`**: beide leveren via de `GlobalExceptionHandler` exact dezelfde 400 + `{"error": …}`-body op. De meldingstekst is letterlijk overgenomen, inclusief de `onbekende fout`-fallback.
- **`PodcastFeedFetcher` ongewijzigd** — inclusief de SSRF-check en de external-call-logging; alleen de aanroeper verschoof van api- naar domain-laag.
- **Extra unit-test toegevoegd** (`PodcastFeedsServiceImplTest`, 4 tests) hoewel niet verplicht: dekt selectief fetchen, de volgorde opslaan-vóór-triggeren, en beide foutmelding-varianten.

## Wat is getest

- **Volledige build** `mvn clean verify`: BUILD SUCCESS — 84/84 unit-tests (incl. `ModuleStructureTest` en de nieuwe testklasse) en 65/65 e2e-tests, 0 failures/errors. `PodcastIngestE2eTest` is ongewijzigd en groen.
- **Statische acceptatiechecks**: nul van de 14 `@RestController`-klassen importeert nog uit `domain`/`infrastructure`; `specs/openapi.yaml` en `PodcastIngestE2eTest.kt` hebben 0 diff t.o.v. main.
- **Live preview (pnf-pr-197)**, API-gedrag van `PUT /api/podcast-feeds` met een wegwerp-account (na afloop verwijderd): onbereikbare feed → 400 en niets opgeslagen; geldige feed → 200 en persistent; een al bestaande URL opnieuw meesturen → 200 zónder nieuwe fetch (bevestigd in de backend-logs); bestaande + nieuwe kapotte URL → 400 met ongewijzigde lijst. De volgorde valideren → opslaan → triggeren is in de logs bevestigd.
- **Browsertest**: via Instellingen → RSS feeds een onbereikbare podcast-URL toegevoegd → rode snackbar met de verwachte Nederlandse melding, bestaande feeds blijven staan.

## Bewust niet gedaan

- `specs/openapi.yaml` niet aangeraakt (geen API-wijziging).
- De andere bevindingen uit `docs/kwaliteitsanalyse-backend.md` (cycle rss ↔ podcast_source, `StartupRunner`, DTO-scheiding) blijven open.
- Frontend en `frontend-reader` ongewijzigd.
- `ModuleStructureTest` niet uitgebreid met een laag-check (Spring Modulith bewaakt modulegrenzen, geen laaggrenzen) — kandidaat voor een vervolgstory.
- `docs/kwaliteitsanalyse-backend.md` niet bijgewerkt (momentopname, geen living doc).

## Aandachtspunten voor de PO

1. **Bestaand gedrag, niet door deze story veroorzaakt**: een blanco podcast-URL wordt door de fetch-lus overgeslagen maar daarna alsnog door `SettingsService` afgewezen met 400 `{"error":"Ongeldige podcast-feed-URL '  ': ongeldige URL"}` — identiek aan main. Eventueel apart oppakken.
2. **Restje verouderde documentatie**: `podcast_source/domain/PodcastIngestionPipeline.kt:63` noemt in KDoc nog `SettingsController`. Viel buiten de acceptatiecriteria van deze story (die betroffen alleen `PodcastIngestionTrigger.kt`); kleine opruimklus voor later.

Beide punten zijn door de reviewer/tester als niet-blokkerend gemarkeerd; de story is inhoudelijk compleet.
