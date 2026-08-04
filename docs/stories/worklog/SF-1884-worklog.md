# SF-1884 - Worklog

Story-context bij eerste pickup:
SharedFeedItem-schema toevoegen en /api/shared/feed-contract corrigeren in openapi.yaml

Uitsluitend documentatie in specs/. Geen backend-, frontend- of testwijzigingen.

1. Voeg in specs/openapi.yaml onder components/schemas een nieuw schema SharedFeedItem toe, direct na het FeedItem-schema (dat loopt van regel ~1482 t/m ~1568), in dezelfde stijl en indentatie als de omliggende schema's. Het schema bevat exact 17 velden, in dezelfde volgorde als SharedFeedItemDto.kt:19-44: id, title, titleNl, summary, shortSummary, url, category, source, sourceRssIds, sourceUrls, topics, feedReason, createdAt, publishedDate, isSummary, mediaType, imageUrl. Geen isRead, starred of liked. Geen required-lijst (FeedItem heeft die ook niet).
2. Neem per overlappend veld hetzelfde type, format, default, enum en nullable-gedrag over als in FeedItem (createdAt: string/date-time; publishedDate: string/nullable met example; isSummary: boolean/default false; mediaType: enum [ARTICLE, PODCAST]/default ARTICLE; imageUrl: string/nullable; sourceUrls: array van string/format uri). Enige bewuste afwijking: url krijgt nullable: true, omdat SharedFeedItemDto.url een Kotlin String? is. Pas het bestaande FeedItem-schema hiervoor NIET aan.
3. Let op de JSON-namen: het String-veld heet summary (niet de getter) en het boolean-veld heet isSummary - beide komen uit de @JsonProperty-annotaties in het DTO.
4. Veldbeschrijvingen mogen uit FeedItem worden overgenomen of ingekort; alleen types/nullability zijn hard.
5. Laat de 200-respons van GET /api/shared/feed (openapi.yaml:988) verwijzen naar '#/components/schemas/SharedFeedItem' in plaats van naar FeedItem. In dat pad-blok mag daarna geen verwijzing naar FeedItem meer staan. De $ref naar FeedItem op regel ~522 (GET /api/feed) blijft ongewijzigd.
6. Herschrijf de description van GET /api/shared/feed (openapi.yaml:978): 'Geen authenticatie vereist' blijft staan; de zin 'Items zijn altijd als ongelezen/niet-gesterd gemarkeerd' vervalt en wordt vervangen door de mededeling dat de persoonlijke vlaggen isRead, starred en liked niet worden meegestuurd, met de korte reden dat het leesgedrag van de bron-gebruiker zo niet kan lekken.
7. Optioneel, alleen als het zonder extra risico kan: voeg app.shared-feed.username (omgevingsvariabele: -, standaard 'robbert', gelezen in SharedFeedController.kt:28) toe als extra rij aan de configuratietabel in specs/backend-functional-spec.md §8.

Controleer zelf voor je afrondt: het schema veld voor veld naast SharedFeedItemDto.kt:19-44 leggen, openapi.yaml is nog geldige/parseerbare YAML+OpenAPI (geen dangling $ref, geen dubbele schemanaam, consistente indentatie), en er zijn geen wijzigingen buiten specs/openapi.yaml en optioneel specs/backend-functional-spec.md. Doe daarna een korte review-stap op je eigen diff.

Stappenplan:
[ ]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `specs/openapi.yaml`: nieuw schema `SharedFeedItem` toegevoegd direct na `FeedItem`
  (voor de `# Requests`-sectie), met exact de 17 velden uit `SharedFeedItemDto.kt:19-44`
  in dezelfde volgorde, zonder `isRead`/`starred`/`liked` en zonder `required`-lijst.
  Types/format/default/enum/nullable zijn 1-op-1 overgenomen uit `FeedItem`; enige bewuste
  afwijking is `url` met `nullable: true` (Kotlin `String?`). `FeedItem` zelf is ongewijzigd.
- `specs/openapi.yaml`: de 200-respons van `GET /api/shared/feed` verwijst nu naar
  `#/components/schemas/SharedFeedItem`; de enige overgebleven `$ref` naar `FeedItem`
  staat bij `GET /api/feed` (regel 522).
- `specs/openapi.yaml`: description van `GET /api/shared/feed` herschreven — "Geen
  authenticatie vereist" blijft, de ongelezen/niet-gesterd-zin is vervangen door de
  mededeling dat `isRead`/`starred`/`liked` niet worden meegestuurd zodat het leesgedrag
  van de bron-gebruiker niet lekt.
- `specs/backend-functional-spec.md` §8: rij `app.shared-feed.username` (standaard
  `robbert`, `SharedFeedController.kt:28`) aan de configuratietabel toegevoegd.
- Verificatie: het bestand geparsed met SnakeYAML (uit de Maven-repo; pyyaml/js-yaml
  ontbreken in de runner) en programmatisch gecontroleerd — 17 properties in de juiste
  volgorde, geen `required`, alle overlappende velden identiek aan `FeedItem` op `url` na,
  geen dangling `$ref` en geen dubbele schemanaam.
- Geen code- of testwijziging nodig (documentatie-only). Vangnet `mvn -B clean verify`
  in `newsfeedbackend/newsfeedbackend`: BUILD SUCCESS, 61 e2e + unit-tests, 0 failures,
  0 errors, ~3:08 min.
