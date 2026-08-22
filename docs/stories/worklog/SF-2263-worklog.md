# SF-2263 - Worklog

Story-context bij eerste pickup:
Voeg frontend/test/models_test.dart toe met de zes testgevallen voor FeedItem en RssItem

Maak uitsluitend het nieuwe bestand frontend/test/models_test.dart aan. Geen enkele wijziging onder frontend/lib/, geen wijziging aan pubspec.yaml/pubspec.lock, geen nieuwe dependency, geen wijziging aan de bestaande negen testbestanden. Import: package:personal_news_feed/models/models.dart (pakketnaam is personal_news_feed, NIET newsfeed; een verkeerde naam faalt met een vage 'loading ...'-fout en laat misleidend admin_screen_test.dart in de failure-lijst opduiken). models.dart heeft nul imports: kale test(...)-blokken volstaan, geen mocks, geen TestWidgetsFlutterBinding.

Dek deze zes gevallen, bij voorkeur in twee groups (FeedItem, RssItem):
1. FeedItem.fromJson met een volledige payload: alle twintig velden gevuld, veldnamen ontleend aan newsfeedbackend/.../feed/api/dto/FeedItemDto.kt; assert dat alle twintig op het juiste veld landen. Zet summary, isRead en isSummary hardgecodeerd in de testdata (die hebben aan backendkant expliciete @JsonProperty-annotaties). createdAt komt als ISO-8601-string over de lijn.
2. FeedItem.fromJson({}) - lege payload: category == 'overig', mediaType == 'ARTICLE', topics/sourceRssIds/sourceUrls zijn lege lijsten, url/liked/imageUrl blijven null.
3. listPreview (models.dart:61), beide takken: gevulde shortSummary wordt teruggegeven; lege shortSummary valt terug op summary met samengevouwen en getrimde witruimte.
4. FeedItem.copyWith (models.dart:87, sentinel op :102), drie losse asserties: copyWith(isRead: true) op een item met liked == true laat liked op true; copyWith(liked: false) zet false; copyWith(liked: null) zet echt null. Schrijf liked: null letterlijk in de aanroep, niet via een tussenvariabele die de sentinel-default zou herstellen.
5. RssItem.fromJson({}): minimaal category == 'overig', summarySource == 'transcript', keyTakeaways lege lijst, durationSeconds null.
6. RssItem.copyWith(liked: null) (models.dart:225): dezelfde sentinel-assertie.

Verificatie en worklog: draai in frontend/ `flutter pub get` en `flutter test` - alles groen, de 40 bestaande tests onaangeroerd en slagend. Draai daarna `flutter test --coverage` en noteer in docs/stories/worklog/SF-2263-worklog.md de LH/LF voor lib/models/models.dart uit coverage/lcov.info (ondergrens LH >= 82 bij LF 200; referentieprobe haalde 109/200) plus het app-brede dekkingspercentage voor en na (baseline 668/2785 = 24,0%). Verwijder daarna de coverage/-map. Controleer dat `git status` uitsluitend het nieuwe testbestand en het worklog toont en dat pubspec.lock ongewijzigd is; is de lockfile toch gemuteerd, draai die diff dan terug.

Melden, niet repareren: noteer in het worklog dat NewsRequest.fromJson (models.dart:337) j['isHourlyUpdate'] ?? j['isDailyUpdate'] ?? false leest terwijl isDailyUpdate nergens meer in de backend of specs/openapi.yaml voorkomt (JacksonConfig.kt:27 documenteert de hernoeming) - dode terugvalcode, aparte bewuste beslissing. Legt een test een echt verschil met het bedoelde gedrag bloot, meld dat dan in het worklog in plaats van de assertie aan te passen of het stilzwijgend te repareren. Sluit af met een korte zelfreview van de diff op scope (alleen het testbestand + worklog) en op leesbaarheid/naamgeving van de tests.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `frontend/test/models_test.dart` toegevoegd (9 tests, twee groups: `FeedItem` en
  `RssItem`) met de zes gevraagde gevallen. Geen enkele wijziging onder `frontend/lib/`,
  geen dependency- of lockfile-wijziging.

## Dekking van de zes gevallen

| # | Geval | Test(s) |
|---|---|---|
| 1 | `FeedItem.fromJson`, volledige payload (20 velden) | `fromJson leest een volledige payload veld voor veld uit` |
| 2 | `FeedItem.fromJson({})`, terugvallen | `fromJson met een lege payload valt terug op de defaults` |
| 3 | `listPreview`, beide takken | 2 losse tests (shortSummary gevuld / terugval op `summary` met samengevouwen witruimte) |
| 4 | `FeedItem.copyWith` + drietrapswaarde `liked` | 3 losse tests (`isRead: true` laat `liked` staan, `liked: false`, `liked: null`) |
| 5 | `RssItem.fromJson({})`, terugvallen | `fromJson met een lege payload valt terug op de defaults` (alle 24 velden) |
| 6 | `RssItem.copyWith(liked: null)` | `copyWith(liked: null) zet liked écht op null` (incl. tegenproef zonder argument) |

`liked: null` staat in beide sentinel-tests letterlijk in de aanroep, niet via een
tussenvariabele — anders zou de sentinel-default weer opduiken en bewaakt de test niets.

## Meetwaarden

Gemeten in `frontend/` met Flutter 3.44.7 (`flutter test --coverage`, `coverage/lcov.info`):

| Meting | Vóór | Ná |
|---|---|---|
| `lib/models/models.dart` | `LH: 11` / `LF: 200` | `LH: 107` / `LF: 200` |
| App-breed (som van alle `LH`/`LF`) | 669/2785 = **24,0 %** | 765/2785 = **27,5 %** |
| `flutter test` | 40 groen | 49 groen (40 bestaande onaangeroerd + 9 nieuwe) |

`LH: 107` ligt ruim boven de ondergrens van 82 uit AC 3 (referentieprobe tijdens
refinement: 109/200 — het verschil van twee regels zit in de gekozen indeling van de
`listPreview`- en `copyWith`-tests, alle zes gevallen zijn gedekt).

Overige verificatie:
- `flutter analyze`: 6 pre-existing infos (feed_screen.dart:189, podcast_detail_screen.dart:278,
  rss_detail_screen.dart:64, rss_screen.dart:67/78/228) — ongewijzigd, geen nieuwe meldingen.
- `coverage/` na de meting verwijderd; `frontend/pubspec.lock` ongewijzigd (`git status` schoon
  op de lockfile).
- Backend-vangnet `mvn -B --no-transfer-progress clean verify`: exit 0, 142 unit + 77 e2e,
  0 failures / 0 errors, 4:21 min, `jacoco.exec` 373.558 B + `jacoco-it.exec` 9,2 MB.
- `git status` toont uitsluitend `frontend/test/models_test.dart` en dit worklog.

## Signalering — niet gerepareerd

`NewsRequest.fromJson` (`frontend/lib/models/models.dart:335`) leest
`isHourlyUpdate: j['isHourlyUpdate'] ?? j['isDailyUpdate'] ?? false`. De veldnaam
`isDailyUpdate` komt nergens meer voor in de backend of in `specs/openapi.yaml` — de enige
treffer in de hele backend is de KDoc-regel `JacksonConfig.kt:27`, die de hernoeming naar
`isHourlyUpdate` juist beschrijft als reden om onbekende velden te negeren
(`FAIL_ON_UNKNOWN_PROPERTIES` uit). De `?? j['isDailyUpdate']`-tak is daarmee dode
terugvalcode. Bewust niet aangeraakt: dat is een aparte, bewuste beslissing (opruimen of
juist documenteren als bescherming tegen oude, lokaal gecachete JSON) en valt buiten de
scope van deze audit-story.

Geen enkele test legde een verschil met het bedoelde gedrag bloot: alle negen asserties
slaagden in één keer tegen de huidige implementatie. Er is dus geen assertie aangepast en
niets stilzwijgend gerepareerd.

## Zelfreview van de diff

- **Scope**: exact twee bestanden — het nieuwe testbestand en dit worklog. Geen productiecode,
  geen `pubspec.yaml`/`pubspec.lock`, geen wijziging aan de bestaande negen testbestanden.
- **Leesbaarheid/naamgeving**: Nederlandstalige testnamen in de stijl van de bestaande suite
  (`podcast_in_progress_statuses_test.dart`), twee `group`s per model, één assertie-onderwerp
  per test zodat een rode test direct het geval aanwijst. Comments leggen alleen het *waarom*
  vast dat niet uit de code blijkt: de herkomst van de veldnamen (`FeedItemDto.kt`), de reden
  voor de hardgecodeerde `@JsonProperty`-velden, welke drie velden bewust géén terugval hebben,
  en waarom `liked: null` letterlijk in de aanroep moet staan.

## SF-2265 — Story-brede test (tester)

Onafhankelijk nagemeten in `frontend/` met Flutter 3.44.7; niets gewijzigd aan code of tests.

| Check | Resultaat |
|---|---|
| `flutter pub get` | OK, `pubspec.lock` en `pubspec.yaml` blijven ongewijzigd |
| `flutter test` | **exit 0**, 49 tests groen, 0 failures / 0 errors |
| `flutter test --coverage` | **exit 0**, 49 groen |
| `lib/models/models.dart` ná | `LH: 107` / `LF: 200` — ruim boven de ondergrens 82 uit AC 3 |
| App-breed ná | 765/2785 = **27,5 %** |
| Baseline (zelfde suite zónder `models_test.dart`, 40 tests groen) | `models.dart` `LH: 11`/`LF: 200`; app-breed 669/2785 = **24,0 %** |

De vóór-cijfers uit het worklog zijn dus zelfstandig gereproduceerd: de nieuwe tests tillen
`models.dart` van 11 naar 107 gedekte regels en de app-brede dekking van 24,0 % naar 27,5 %.

Acceptatiecriteria: AC 1 t/m 7 voldaan. De zes gevallen zitten alle in
`frontend/test/models_test.dart` (9 tests); `git diff main...HEAD` toont uitsluitend dat
testbestand en dit worklog — geen enkel bestand onder `frontend/lib/`, geen `coverage/`,
geen lockfile. De `coverage/`-map van mijn eigen metingen is na afloop verwijderd.

Preview-verificatie (PR 240, `https://pnf-pr-240.vdzonsoftware.nl`), screenshots in
`/work/screenshots`:
- De story raakt geen productiecode, dus er is geen story-specifiek UI-gedrag te zien; de
  preview is als rooktest gebruikt. Login-scherm, Feed-tab, RSS-tab en Instellingen-tab
  renderen normaal; Instellingen toont frontend- én backend-build `1f5d7b2`, gelijk aan de
  branch-commit. Geen console-fouten buiten de hieronder genoemde 403.

Signaleringen voor een volgende ronde (niet gerepareerd, buiten scope van deze story):
- **Inlog-modus**: de default-modus (vaste test-user uit het namespace-secret) was niet
  beschikbaar — `TESTER_USERNAME`/`TESTER_PASSWORD` zijn niet gezet in deze harness en de
  serviceaccount `agent-access:claude-agent` mag `secrets/newsfeed-api-keys` in `pnf-pr-240`
  niet lezen (`Forbidden`). Volgens contract teruggevallen op het wegwerp-account
  `tester_sf-2265`, via de UI geregistreerd.
- **Restant testdata**: het opruimen van dat wegwerp-account is *niet* gelukt.
  `DELETE /api/account/me` gaf **403**: het bearer-token is uit `localStorage` gehaald met een
  heuristiek, terwijl de app het via `SharedPreferences` onder `flutter.token` opslaat
  (`auth_provider.dart:47`), en het wachtwoord was per run willekeurig gegenereerd en dus niet
  opnieuw bruikbaar. De user `tester_sf-2265` blijft daardoor in de preview-DB van PR 240
  achter, zonder feeds of categorieën. Die per-PR Neon-branch wordt bij PR-close in zijn geheel
  opgeruimd, dus er blijft niets buiten de preview staan. Productie is niet geraakt.
- Het worklog verwijst voor de `isDailyUpdate`-signalering naar `models.dart:335`; de regel
  staat feitelijk op **`models.dart:337`** (zoals de story ook noemt). Puur een regelnummer,
  de signalering zelf klopt.
