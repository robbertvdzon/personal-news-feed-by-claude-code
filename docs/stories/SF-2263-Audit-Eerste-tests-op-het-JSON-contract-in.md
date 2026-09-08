# SF-2263 - [Audit] Eerste tests op het JSON-contract in de hoofdapp: `FeedItem` en `RssItem`

## Story

[Audit] Eerste tests op het JSON-contract in de hoofdapp: `FeedItem` en `RssItem`

<!-- refined-by-factory -->

## Scope

Voeg één nieuw testbestand toe: **`frontend/test/models_test.dart`**. Geen wijziging aan productiecode, geen nieuwe dependency, geen wijziging aan bestaande tests of aan `pubspec.yaml`/`pubspec.lock`.

`frontend/lib/models/models.dart` bevat zeven modelklassen met elk een `fromJson`-fabriek. Van de 200 uitvoerbare regels zijn er vandaag 11 gedekt (gemeten) en draait geen enkele `fromJson`. Deze story dekt de eerste twee modellen: `FeedItem` en `RssItem` — de twee die door de feed- en RSS-schermen stromen en de enige twee met de `_Sentinel`-constructie in `copyWith`.

`models.dart` heeft nul imports, dus er is geen opzet, geen mock en geen `TestWidgetsFlutterBinding` nodig; kale `test(...)`-blokken volstaan. De import in het testbestand is `package:personal_news_feed/models/models.dart` (de pakketnaam is `personal_news_feed`, niet `newsfeed`).

Te dekken gevallen:

1. **`FeedItem.fromJson` met een volledige payload.** Alle twintig velden gevuld; assert dat ze alle twintig op het juiste veld landen. Bron voor de veldnamen is `newsfeedbackend/.../feed/api/dto/FeedItemDto.kt`, het DTO dat de backend serialiseert; die twee komen vandaag veld voor veld één-op-één overeen en deze test legt dat vast. De drie velden met een expliciete `@JsonProperty` aan de backendkant (`summary`, `isRead`, `isSummary`) staan hardgecodeerd in de testdata — die annotaties bestaan omdat Jackson ze anders hernoemt.
2. **`FeedItem.fromJson({})` — lege payload.** Assert de terugvallen: `category` = `'overig'`, `mediaType` = `'ARTICLE'`, `topics`/`sourceRssIds`/`sourceUrls` zijn lege lijsten, en `url`/`liked`/`imageUrl` blijven `null` (die hebben bewust géén terugval).
3. **`listPreview`.** Beide takken: `shortSummary` gevuld → die wordt teruggegeven; `shortSummary` leeg → `summary` met opeenvolgende witruimte samengevouwen en getrimd.
4. **`FeedItem.copyWith` en de drietrapswaarde `liked`.** Drie aparte asserties: `copyWith(isRead: true)` op een item met `liked == true` laat `liked` op `true`; `copyWith(liked: false)` zet hem op `false`; `copyWith(liked: null)` zet hem écht op `null` in plaats van hem te laten staan. Die laatste is de reden dat de `_Sentinel`-constructie bestaat en de enige die bij een "opruimende" refactor stil omslaat; `data_providers.dart:92` en `:154` roepen `copyWith(liked: ...)` aan voor de optimistische UI-update van de duim-omhoog/duim-omlaag/geen-mening-knop.
5. **`RssItem.fromJson({})`** — dezelfde terugval-test voor het tweede model, met in elk geval `category` (`'overig'`), `summarySource` (`'transcript'`), `keyTakeaways` (lege lijst) en `durationSeconds` (`null`).
6. **`RssItem.copyWith(liked: null)`** — dezelfde sentinel-assertie als bij `FeedItem`; het is een letterlijke kopie van dezelfde regel en dus dezelfde valkuil.

## Acceptance criteria

1. `frontend/test/models_test.dart` bestaat en bevat tests voor alle zes bovenstaande gevallen. Geen enkel bestand onder `frontend/lib/` is gewijzigd.
2. `flutter pub get && flutter test` in `frontend/` is groen. De 40 bestaande tests blijven onaangeroerd en blijven slagen; het totaal loopt op met het aantal nieuwe tests.
3. `flutter test --coverage` laat voor `lib/models/models.dart` in `coverage/lcov.info` een `LH` van **minstens 82** bij `LF: 200` zien (referentieprobe tijdens refinement haalde 109/200 en tilde de app-brede dekking van 24,0% naar 27,5%). Blijf je noemenswaardig onder de 82, dan mis je waarschijnlijk een van de twee `copyWith`-varianten.
4. Het worklog vermeldt de gemeten `LH/LF` voor `lib/models/models.dart` en het app-brede dekkingspercentage vóór en na.
5. Het worklog vermeldt als losse signalering — **niet repareren** — dat `NewsRequest.fromJson` (`models.dart:337`) `j['isHourlyUpdate'] ?? j['isDailyUpdate'] ?? false` leest, terwijl `isDailyUpdate` nergens meer in de backend of in `specs/openapi.yaml` voorkomt; `JacksonConfig.kt:27` documenteert de hernoeming zelfs als reden om onbekende velden te negeren. Dode terugvalcode, stof voor een aparte bewuste beslissing.
6. `git status` toont uitsluitend het nieuwe testbestand (plus het worklog). De `coverage/`-map wordt na de meting verwijderd; `pubspec.lock` is ongewijzigd.
7. Legt een test een echt verschil met het bedoelde gedrag bloot, dan wordt dat in het worklog gemeld in plaats van dat de assertie wordt aangepast of het stilzwijgend wordt gerepareerd.

## Aannames

- De pakketnaam in de import is `personal_news_feed` (bevestigd via `frontend/pubspec.yaml` en het bestaande `test/podcast_in_progress_statuses_test.dart`).
- `flutter pub get` in `frontend/` muteert `pubspec.lock` niet in de agent-container (gemeten met Flutter 3.44.7: diff leeg). Wijkt dat toch af, dan wordt de lockfile-diff vóór de commit teruggedraaid.
- `coverage/` staat al in `frontend/.gitignore:34`; opruimen is hygiëne, geen commitrisico.
- De ijkgetallen "82/200" en "26,6%" uit de oorspronkelijke storytekst zijn een ondergrens, geen doelwaarde — een probe met precies deze zes gevallen haalde 109/200 en 27,5%. AC 3 is daarom als ondergrens geformuleerd.
- `createdAt` is aan de backendkant een `Instant` en komt als ISO-8601-string over de lijn; de testdata gebruikt dus een string voor dat veld.
- CI pikt het nieuwe bestand automatisch op via de bestaande `flutter test (frontend)`-job; geen workflow-wijziging nodig.

## Buiten scope

- Alle productiecode; expliciet ook de dode `isDailyUpdate`-terugval (alleen signaleren).
- De overige vijf modellen (`PodcastFeed`, `CategorySettings`, `NewsRequest`, `Podcast`, `EpisodeLookup`) — logische tweede stap zodra het patroon staat.
- De schermen, de providers, de leesapp (`frontend-reader`) en de backend.

## Eindsamenvatting

## Eindsamenvatting SF-2263 — [Audit] Eerste tests op het JSON-contract in de hoofdapp: `FeedItem` en `RssItem`

**Wat is gebouwd**
Eén nieuw testbestand: `frontend/test/models_test.dart` (9 tests, twee `group`s: `FeedItem` en `RssItem`). Geen productiecode gewijzigd, geen nieuwe dependency, geen wijziging aan `pubspec.yaml`/`pubspec.lock` of aan de bestaande negen testbestanden. De diff tegen `main` bestaat uit exact twee bestanden: het testbestand en het worklog.

De zes gevraagde gevallen zijn alle gedekt:
1. `FeedItem.fromJson` met volledige payload — alle twintig velden, veldnamen ontleend aan `FeedItemDto.kt`, inclusief de drie `@JsonProperty`-velden (`summary`, `isRead`, `isSummary`) en `createdAt` als ISO-8601-string.
2. `FeedItem.fromJson({})` — terugvallen (`category`='overig', `mediaType`='ARTICLE', lege lijsten) én de drie velden die bewust géén terugval hebben (`url`/`liked`/`imageUrl` blijven `null`).
3. `listPreview` — beide takken (gevulde `shortSummary` vs. terugval op `summary` met samengevouwen/getrimde witruimte).
4. `FeedItem.copyWith` met de drietrapswaarde `liked` — drie losse tests, waaronder de sentinel-assertie `copyWith(liked: null)`.
5. `RssItem.fromJson({})` — terugvallen over alle velden.
6. `RssItem.copyWith(liked: null)` — dezelfde sentinel-assertie, met tegenproef.

**Gemaakte keuzes**
- `liked: null` staat in beide sentinel-tests letterlijk in de aanroep, niet via een tussenvariabele; anders zou de `_Sentinel`-default weer opduiken en bewaakt de test niets. Dit is precies de assertie die bij een "opruimende" refactor stil zou omslaan (`data_providers.dart:92`/`:154` gebruiken deze aanroep voor de optimistische duim-omhoog/omlaag-update).
- Nederlandstalige testnamen in de stijl van de bestaande suite, één assertie-onderwerp per test zodat een rode test direct het geval aanwijst.
- Comments leggen alleen het *waarom* vast: herkomst van de veldnamen, reden voor de hardgecodeerde `@JsonProperty`-velden, welke velden bewust geen terugval hebben.

**Wat is getest (en door de tester onafhankelijk nagemeten)**
| Meting | Vóór | Ná |
|---|---|---|
| `lib/models/models.dart` | LH 11 / LF 200 | **LH 107 / LF 200** (AC-ondergrens: 82) |
| App-brede dekking | 669/2785 = 24,0 % | **765/2785 = 27,5 %** |
| `flutter test` | 40 groen | **49 groen**, 0 failures |

Verder: `flutter analyze` toont alleen de 6 pre-existing infos (ongewijzigd); `coverage/` na de meting verwijderd; `pubspec.lock` ongewijzigd; backend-vangnet `mvn clean verify` groen (142 unit + 77 e2e). Preview PR 240 als rooktest: login, Feed, RSS en Instellingen renderen normaal, buildhash gelijk aan de branch-commit. **Alle 7 acceptatiecriteria zijn voldaan.** Geen enkele test legde een verschil met het bedoelde gedrag bloot; er is geen assertie aangepast.

**Bewust niet gedaan / signaleringen**
- **Dode terugvalcode gemeld, niet gerepareerd**: `NewsRequest.fromJson` (`models.dart:337`) leest `j['isHourlyUpdate'] ?? j['isDailyUpdate'] ?? false`, terwijl `isDailyUpdate` nergens meer in de backend of `specs/openapi.yaml` voorkomt. Vraagt om een aparte, bewuste beslissing (opruimen of documenteren als bescherming tegen oude gecachete JSON).
- De overige vijf modellen (`PodcastFeed`, `CategorySettings`, `NewsRequest`, `Podcast`, `EpisodeLookup`) zijn buiten scope gebleven — logische tweede stap nu het patroon staat.
- **Restant testdata in de preview**: het wegwerp-testaccount `tester_sf-2265` kon niet worden opgeruimd (`DELETE /api/account/me` gaf 403; het token staat in `SharedPreferences` onder `flutter.token`, niet waar de opruim-heuristiek keek). Het account blijft leeg achter in de preview-database van PR 240, die bij PR-close in zijn geheel wordt opgeruimd. **Productie is niet geraakt.**
- Klein detail: het worklog noemt bij de signalering `models.dart:335`, de regel staat op `:337`.

```json
```
