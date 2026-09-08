# SF-2123 - [Audit] Maak de nieuwe "één gedeelde statuslijst"-regel kloppend: verwijs naar `EpisodeLookup` en geef ook de vertaalflow-lijst één naam

## Story

[Audit] Maak de nieuwe "één gedeelde statuslijst"-regel kloppend: verwijs naar `EpisodeLookup` en geef ook de vertaalflow-lijst één naam

<!-- refined-by-factory -->

## Scope

SF-2066 introduceerde de gedeelde constante `kPodcastInProgressStatuses` (`frontend/lib/models/models.dart:372-379`) plus een huisregel daarover. In die regel zit één feitelijke fout die op drie plekken is overgenomen: de bewuste uitzondering heet overal `Podcast.translationInProgress`, terwijl de klasse `Podcast` (`models.dart:381-459`) die property niet heeft. De getter staat op `EpisodeLookup` (`models.dart:516-518`) en gaat over `translatedPodcastStatus` — de status van de vertaling van één RSS-aflevering, niet van de podcast zelf.

Daarnaast staat de smallere driewaardenlijst nu twee keer zonder naam in de code. Dit is documentatiecorrectie plus opruimwerk; **het gedrag van de app is na deze story exact identiek**.

Concreet:

1. **Naam corrigeren op drie plekken** — `docs/factory/technical-spec.md:85`, `specs/frontend-spec.md:278` en de comment bij de gedeelde constante zelf (`frontend/lib/models/models.dart:369`). Overal moet het `EpisodeLookup.translationInProgress` zijn. Voeg in de comment bij de gedeelde constante één halve zin toe over wát die smallere lijst is (de statussen van de vertaalflow van één RSS-aflevering), zodat het verschil met de gedeelde set duidelijk is zonder de code te openen.

2. **De smallere lijst één naam geven** — hij staat nu als kaal literal op `models.dart:517-518` (`const ['PENDING', 'TRANSLATING', 'TTS_GENERATING'].contains(...)`) en als `switch`-cases in `_phaseLabel` (`frontend/lib/screens/rss_podcast_detail_screen.dart:707-718`), dat het label rendert dat direct naast diezelfde conditie op `:646` staat. Voeg naast `kPodcastInProgressStatuses` een tweede top-level constante toe, bijvoorbeeld `const kPodcastTranslationInProgressStatuses = <String>{'PENDING', 'TRANSLATING', 'TTS_GENERATING'}`, met een comment die uitlegt dat dit bewust een deelverzameling van de gedeelde set is en waarom (een vertaling doorloopt nooit de generatie-statussen). Laat `EpisodeLookup.translationInProgress` die set gebruiken. `_phaseLabel` blijft een `switch` — dat is een labelvertaling, geen lijstvraag — maar krijgt er een korte comment bij die naar de nieuwe constante verwijst, zodat wie een status toevoegt beide plekken ziet.

3. **Test als vangrail** — breid `frontend/test/podcast_in_progress_statuses_test.dart` uit (of voeg een test toe in `frontend/test/`) die vastlegt dat de nieuwe constante exact die drie statussen bevat én dat hij een echte deelverzameling van `kPodcastInProgressStatuses` is. Dat laatste is de eigenlijke vangrail: loopt er ooit een status uit de twee lijsten, dan valt die test om.

### Buiten scope (bewust)

- Het uitfactoren van `_statusLabel` (22 regels letterlijk dubbel in `podcast_screen.dart:18-39` en `podcast_detail_screen.dart:255-276`).
- Het gelijktrekken van het hoofdlettergebruik tussen `_phaseLabel` en `_statusLabel`.
- De duplicatie tussen `feed_screen.dart` en `rss_screen.dart`.
- Alles in `frontend-reader`.
- Backend-, contract- (`specs/openapi.yaml`) of databasewijzigingen.
- Afgesloten story-verslagen onder `docs/stories/` (o.a. `SF-2066-*.md` r37 en r75) die dezelfde verkeerde naam noemen: dat zijn historische records, geen levende huisregels.

## Acceptance criteria

1. `grep -rn "Podcast.translationInProgress"` over `frontend/`, `specs/` en `docs/factory/` geeft nul treffers; op de drie genoemde plekken staat `EpisodeLookup.translationInProgress`.
2. De comment bij `kPodcastInProgressStatuses` (`models.dart:~369`) benoemt naast de juiste naam ook in één halve zin wát de smallere lijst is: de statussen van de vertaalflow van één RSS-aflevering.
3. In `frontend/lib/models/models.dart` staat naast `kPodcastInProgressStatuses` een tweede top-level constante met de drie vertaalstatussen (`Set<String>`, `k`-prefix conform bestaande conventie), met een comment die uitlegt dat dit bewust een deelverzameling van de gedeelde set is en waarom.
4. `EpisodeLookup.translationInProgress` gebruikt die nieuwe constante; er staat geen kaal statusliteral meer in die getter.
5. `_phaseLabel` in `rss_podcast_detail_screen.dart` is nog steeds een `switch` met dezelfde cases en dezelfde teruggegeven labels (inclusief de `default`-tak `'bezig…'`), maar heeft er een korte comment bij die naar de nieuwe constante verwijst.
6. Er is een test in `frontend/test/` die (a) asserteert dat de nieuwe constante exact `{'PENDING', 'TRANSLATING', 'TTS_GENERATING'}` is, en (b) asserteert dat de nieuwe constante een deelverzameling is van `kPodcastInProgressStatuses` (bijv. via `containsAll`/`every`).
7. `flutter test` in `frontend/` slaagt volledig; het bestaande aantal van 27 tests neemt alleen toe (baseline geverifieerd: 27/27 groen vóór deze story). `flutter analyze` levert geen nieuwe waarschuwingen op.
8. Geen gedragswijziging: de statussen waarop de UI reageert en de labels die getoond worden zijn identiek aan vóór deze story. Er wijzigt niets in `newsfeedbackend/`, `specs/openapi.yaml`, database-migraties of `frontend-reader/`.
9. De wijzigingen beperken zich tot: `frontend/lib/models/models.dart`, `frontend/lib/screens/rss_podcast_detail_screen.dart`, `frontend/test/podcast_in_progress_statuses_test.dart`, `docs/factory/technical-spec.md`, `specs/frontend-spec.md`.

## Aannames

- De naam `kPodcastTranslationInProgressStatuses` is een suggestie; een andere duidelijke naam met `k`-prefix mag ook, mits consistent gebruikt in code, comments en test.
- De backend-vertaalflow kent exact `PENDING`, `TRANSLATING`, `TTS_GENERATING`, `DONE` en `FAILED` (geverifieerd in `PodcastTranslator.kt:24-26, 80, 105, 140, 161`). De twee lijsten zijn het daarover eens; er wordt in deze story geen status toegevoegd of verwijderd.
- `_phaseLabel` houdt zijn `default: 'bezig…'`-tak, dus een toekomstige extra status levert een generiek maar niet-kapot label op — de nieuwe comment is de vangrail, niet een codewijziging.
- De huisregel in `docs/factory/technical-spec.md:85` en de blockquote in `specs/frontend-spec.md:274-281` blijven inhoudelijk hetzelfde verhaal; alleen de naam wordt gecorrigeerd en waar nodig kort verduidelijkt.
- `frontend/lib/screens/rss_podcast_detail_screen.dart` importeert `models.dart` al indirect via de gebruikte modellen; als een expliciete import nodig blijkt, is die toevoeging in scope.

## Eindsamenvatting

Ik heb `.task.md`, het worklog (`docs/stories/worklog/SF-2123-worklog.md`) en de volledige story-diff (`main...HEAD`, 6 bestanden) gelezen.

## Eindsamenvatting SF-2123

**Wat er is gebouwd**

Deze story is een documentatie- en opruimstory zonder gedragswijziging. Drie dingen zijn gedaan:

1. **Naamcorrectie op drie levende plekken.** De huisregel uit SF-2066 verwees naar `Podcast.translationInProgress`, een property die niet bestaat. De getter staat op `EpisodeLookup` en gaat over `translatedPodcastStatus` (de vertaling van één RSS-aflevering, niet de podcast zelf). Gecorrigeerd in `docs/factory/technical-spec.md`, `specs/frontend-spec.md` en de comment bij `kPodcastInProgressStatuses` in `frontend/lib/models/models.dart`, inclusief een korte uitleg wát die smallere lijst is.
2. **Tweede constante met een naam.** De drie vertaalstatussen stonden als kaal literal in de getter. Ze staan nu als `const kPodcastTranslationInProgressStatuses = {'PENDING','TRANSLATING','TTS_GENERATING'}` naast de gedeelde set, met een comment die uitlegt dat het bewust een deelverzameling is (een vertaling doorloopt nooit de generatie-statussen). `EpisodeLookup.translationInProgress` leest die constante; het literal is weg.
3. **Vangrail-test.** `frontend/test/podcast_in_progress_statuses_test.dart` kreeg twee tests: exacte inhoud van de nieuwe constante, én dat hij een échte deelverzameling van `kPodcastInProgressStatuses` blijft (`containsAll` + strikt kleiner). Lopen de lijsten ooit uiteen, dan valt die test om.

**Keuzes**

- `_phaseLabel` in `rss_podcast_detail_screen.dart` blijft een `switch` (labelvertaling, geen lijstvraag) en kreeg alleen een doc-comment die naar de nieuwe constante verwijst — de diff bevat daar geen enkele gewijzigde coderegel.
- `const List.contains` werd `Set.contains` op dezelfde drie waarden: identiek gedrag.
- Oude treffers van de foute naam in afgesloten story-verslagen (`docs/stories/SF-2066-*.md`) zijn bewust ongemoeid gelaten: historische records, geen levende huisregels.

**Wat is getest**

- `flutter test` in `frontend/`: 29/29 groen (baseline 27 + 2 nieuwe).
- `flutter analyze` op HEAD én op een schone `main`: beide 7 pre-existing info-lints, na normalisatie identiek → geen nieuwe waarschuwingen.
- Backend `mvn clean verify`: BUILD SUCCESS, 71 e2e-tests, 0 failures.
- `grep -rn "Podcast.translationInProgress"` over `frontend/ specs/ docs/factory/` → nul treffers (AC1).
- Gedragsbewijs op de preview (pnf-pr-224) met gemockte API-responses: alle vijf statussen gerenderd; labels 1-op-1 gelijk aan de switch. `GENERATING_AUDIO` en `FAILED` vallen terecht níet in de vertaal-tak — dat is de discriminator die aantoont dat de smallere set nog steeds smaller is.
- Scope-check: diff raakt exact de vijf toegestane bestanden plus het worklog; `newsfeedbackend/`, `specs/openapi.yaml` en `frontend-reader/` zijn leeg in de diff.

**Bewust niet gedaan**

Uitfactoren van de dubbele `_statusLabel` (22 regels in twee schermen), gelijktrekken van hoofdlettergebruik tussen `_phaseLabel` en `_statusLabel`, de duplicatie tussen `feed_screen.dart` en `rss_screen.dart`, en alles in `frontend-reader`. Ook opgemerkt maar buiten scope: `podcast_detail_screen.dart:26` noemt de vertaalstatussen nog in proza — kandidaat voor een latere story.
