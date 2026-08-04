# SF-1884 - [Audit] [Audit] Documentatie: geef /api/shared/feed in openapi.yaml een eigen responseschema dat klopt met SharedFeedItemDto

## Story

[Audit] [Audit] Documentatie: geef /api/shared/feed in openapi.yaml een eigen responseschema dat klopt met SharedFeedItemDto

<!-- refined-by-factory -->

## Samenvatting

De API-documentatie beschrijft de publieke lees-feed verkeerd. Volgens het document krijgt de reader-app per nieuwsitem ook door of Robbert het al gelezen, gesterd of geliket heeft. In werkelijkheid stuurt de server die drie gegevens bewust niet mee, zodat zijn leesgedrag niet zichtbaar wordt voor bezoekers.

De code doet het dus goed; alleen de documentatie loopt achter. We geven de publieke feed daarom een eigen beschrijving in het API-document die exact klopt met wat de server werkelijk teruggeeft, en leggen kort uit waarom die drie gegevens ontbreken. Er verandert niets aan de werking van de apps.

## Scope

Uitsluitend `specs/openapi.yaml`, plus optioneel één regel in `specs/backend-functional-spec.md`.

1. **Nieuw schema `SharedFeedItem`** onder `components/schemas`, met exact de 17 velden van `SharedFeedItemDto.kt:18-45`: `id`, `title`, `titleNl`, `summary`, `shortSummary`, `url`, `category`, `source`, `sourceRssIds`, `sourceUrls`, `topics`, `feedReason`, `createdAt`, `publishedDate`, `isSummary`, `mediaType`, `imageUrl`.
   - Overlappende velden krijgen dezelfde types, `format`, `default`, `enum` en `nullable` als in het bestaande `FeedItem`-schema (`openapi.yaml:1482-1568`); veldbeschrijvingen mogen overgenomen of ingekort worden.
   - Let op de JSON-namen `summary` (het String-veld, niet de getter) en `isSummary` — die komen uit de `@JsonProperty`-annotaties in het DTO.
   - Plaats het schema logisch bij `FeedItem` in de buurt, in de stijl van de omliggende schema's.
2. **`GET /api/shared/feed`** (`openapi.yaml:988`) verwijst naar `#/components/schemas/SharedFeedItem` in plaats van naar `FeedItem`.
3. **Beschrijving van `GET /api/shared/feed`** (`openapi.yaml:978`) herschrijven: de zin "Items zijn altijd als ongelezen/niet-gesterd gemarkeerd" vervalt en wordt vervangen door de mededeling dat de persoonlijke vlaggen `isRead`, `starred` en `liked` niet worden meegestuurd, met de korte reden dat het leesgedrag van de bron-gebruiker zo niet kan lekken. "Geen authenticatie vereist" blijft staan.
4. **Optioneel** (alleen als het zonder extra risico kan): voeg `app.shared-feed.username` (standaard `robbert`, gelezen in `SharedFeedController.kt:28`) toe aan de configuratietabel in `specs/backend-functional-spec.md` §8.

Buiten scope: alle backend- en frontend-code (het gedrag is correct en blijft ongewijzigd), het `FeedItem`-schema zelf (blijft in gebruik voor `GET /api/feed`, `openapi.yaml:522`), `/api/shared/categories`, en alle tests.

## Acceptance criteria

1. `specs/openapi.yaml` bevat een schema `SharedFeedItem` met precies de 17 hierboven genoemde velden — niet meer, niet minder. Er staan geen `isRead`, `starred` of `liked` in.
2. Elk veld in `SharedFeedItem` heeft hetzelfde type, `format`, `default`, `enum` en `nullable`-gedrag als het gelijknamige veld in `FeedItem`, met uitzondering van `url` (zie Aannames).
3. De `200`-respons van `GET /api/shared/feed` is een array van `#/components/schemas/SharedFeedItem`; er staat geen verwijzing naar `FeedItem` meer in dat pad-blok.
4. De `description` van `GET /api/shared/feed` vermeldt niet langer dat items "altijd als ongelezen/niet-gesterd" zijn, maar dat `isRead`/`starred`/`liked` niet worden meegestuurd, inclusief de korte reden (leesgedrag van de bron-gebruiker lekt zo niet).
5. `#/components/schemas/FeedItem` bestaat ongewijzigd en wordt nog steeds door `GET /api/feed` gebruikt.
6. `specs/openapi.yaml` blijft geldige, parseerbare OpenAPI: geen dangling `$ref`, geen dubbele schemanaam, consistente indentatie met de rest van het bestand.
7. Er zijn geen wijzigingen buiten `specs/openapi.yaml` en (optioneel) `specs/backend-functional-spec.md`.
8. Verificatie is een documentatiecontrole: het nieuwe schema veld voor veld naast `SharedFeedItemDto.kt:18-45` leggen. Er is geen code- of testwijziging nodig; de bestaande build blijft ongewijzigd groen.

## Aannames

- **17 velden, niet 18.** De storytekst noemt "18 velden" maar somt er zelf 17 op; `SharedFeedItemDto.kt:18-45` heeft er geteld 17. We houden 17 aan.
- **`url` wordt `nullable: true`.** In het `FeedItem`-schema staat `url` zonder `nullable`, terwijl zowel `FeedItem.url` als `SharedFeedItemDto.url` in Kotlin `String?` zijn. Omdat de acceptatie een veld-voor-veld-vergelijking met het DTO is, krijgt `SharedFeedItem.url` `nullable: true`. Het bestaande `FeedItem`-schema wordt hiervoor niet aangepast (buiten scope); die afwijking blijft staan en mag als losse vervolgstory gemeld worden.
- Beschrijvingen bij velden mogen uit `FeedItem` worden overgenomen of beknopter; alleen types/nullability zijn hard.
- Er komt geen `required`-lijst in `SharedFeedItem`, omdat `FeedItem` die ook niet heeft en het bestand die stijl nergens hanteert.
- De reader-app en de backend blijven ongewijzigd; deze story lost uitsluitend contract-drift op.

## Eindsamenvatting

# SF-1884 — Eindsamenvatting

**[Audit] Documentatie: `/api/shared/feed` een eigen responseschema geven dat klopt met `SharedFeedItemDto`**

## Wat is gebouwd
Documentatie-only story: contract-drift in de API-specificatie opgelost. Er is geen enkele regel backend- of frontend-code gewijzigd; het gedrag van de apps is identiek gebleven.

- **Nieuw schema `SharedFeedItem`** in `specs/openapi.yaml`, direct na `FeedItem`, met exact de 17 velden uit `SharedFeedItemDto.kt` in dezelfde volgorde. De persoonlijke vlaggen `isRead`, `starred` en `liked` staan er bewust niet in, omdat de server die ook niet meestuurt.
- **`GET /api/shared/feed`** verwijst in de 200-respons nu naar `SharedFeedItem` in plaats van naar `FeedItem`.
- **Beschrijving van `GET /api/shared/feed`** herschreven: de onjuiste zin "items zijn altijd als ongelezen/niet-gesterd gemarkeerd" is vervangen door de mededeling dát de drie persoonlijke vlaggen ontbreken, met de reden (leesgedrag van de bron-gebruiker mag niet lekken). "Geen authenticatie vereist" is blijven staan.
- **`specs/backend-functional-spec.md` §8**: de optionele configuratierij `app.shared-feed.username` (standaard `robbert`) toegevoegd.

Totale diff: 3 bestanden, waarvan één het worklog is.

## Gemaakte keuzes
- **17 velden, niet 18** — de oorspronkelijke storytekst noemde 18 velden maar somde er 17 op; het DTO heeft er 17.
- **`url` krijgt `nullable: true`**, afwijkend van het bestaande `FeedItem`-schema, omdat het veld in Kotlin `String?` is. Dit is live bevestigd (`url` komt daadwerkelijk als `null` voor).
- **`FeedItem` bewust niet aangepast** (buiten scope). Dat schema mist dus nog steeds `nullable: true` op `url` — kandidaat voor een kleine vervolgstory.
- **Geen `required`-lijst** in het nieuwe schema, conform de stijl van de rest van het bestand.

## Wat is getest
- **Statische contractcontrole**: `openapi.yaml` geparsed (SnakeYAML én js-yaml) — geldige OpenAPI, geen dangling `$ref`, geen dubbele schemanaam. Veld-voor-veld vergeleken met het DTO en met `FeedItem`; `FeedItem` is byte-identiek aan `main` en blijft in gebruik voor `GET /api/feed`.
- **Live gedragscontrole op de preview**: `GET /api/shared/feed` zonder token → 200 met 460 items; de verzameling van alle voorkomende JSON-velden is exact de 17 gedocumenteerde velden — niets extra, niets ontbrekend, geen `isRead`/`starred`/`liked`. Enum `mediaType` en de nullability zijn ook live bevestigd. `GET /api/feed` zonder token geeft nog steeds 403.
- **Regressie-vangnet**: `mvn clean verify` in de backend → BUILD SUCCESS, 102 unit-tests + 61 e2e-tests, 0 failures / 0 errors, geen flakes.
- Alle 8 acceptatiecriteria gehaald; review vond geen blockers of bugs.

## Bewust niet gedaan
- Geen backend- of frontend-code gewijzigd — het gedrag was al correct.
- Geen tests toegevoegd of aangepast (documentatie-only).
- `FeedItem`, `/api/shared/categories` en het `/api/feed`-contract ongemoeid gelaten.
- Geen browser-screenshots: er is geen frontend-oppervlak dat door deze wijziging verandert.

<!-- deploy-summary:start -->
De handleiding bij de openbare nieuwsfeed klopte niet helemaal en is nu gecorrigeerd. Daarin staat voortaan duidelijk dat bezoekers niet te zien krijgen wat Robbert al gelezen, gesterd of geliket heeft. Aan de apps zelf verandert niets; alles werkt precies zoals je gewend bent.
<!-- deploy-summary:end -->
