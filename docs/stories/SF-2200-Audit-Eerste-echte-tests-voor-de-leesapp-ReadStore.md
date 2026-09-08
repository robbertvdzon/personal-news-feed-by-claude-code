# SF-2200 - [Audit] Eerste echte tests voor de leesapp: ReadStore en formatRelativeTime

## Story

[Audit] Eerste echte tests voor de leesapp: ReadStore en formatRelativeTime

<!-- refined-by-factory -->

## Scope

De leesapp `frontend-reader` wordt zelfstandig uitgeleverd (eigen APK-workflow `build-apk-reader.yml`, eigen webbuild, eigen CI-job in `frontend-tests.yml`), maar heeft nauwelijks testvangnet: van de 942 regels in `lib` worden er 17 door een test uitgevoerd, allemaal uit `models.dart`. Deze story zet de eerste stap en is bewust klein: **twee nieuwe testbestanden, geen wijziging aan productiecode, geen nieuwe dependency**.

### 1. `frontend-reader/test/read_store_test.dart`

Tests op `lib/local_store.dart` (`ReadStore`). `shared_preferences` staat al in `pubspec.yaml`; gebruik `SharedPreferences.setMockInitialValues({...})` per test en roep `TestWidgetsFlutterBinding.ensureInitialized()` bovenin `main()` aan (anders is er geen platformkanaal voor de mock). `frontend/test/auth_logout_ws_test.dart` gebruikt datzelfde patroon — volg dat.

Te dekken:

- `load()` leest `read_ids` en `starred_ids` terug; een lege opslag levert lege sets op.
- `markRead(id)` is idempotent: de tweede aanroep waarschuwt geen luisteraars meer en schrijft niet opnieuw. Tel de `notifyListeners()`-aanroepen met een teller via `addListener` — dat is de enige manier om "geen no-op-schrijfactie" echt aan te tonen.
- `setRead(id, false)` verwijdert de markering, en ook hier alleen een notificatie bij een échte wijziging.
- `markAllRead(ids)` geeft terug hoeveel items **nieuw** op gelezen zijn gezet: een tweede aanroep met deels overlappende id's telt alleen het verschil, en een aanroep zonder nieuwe id's geeft 0 zonder te schrijven of te waarschuwen.
- `toggleStar(id)` heen en weer. Leg met een commentaarregel vast dat deze methode — anders dan `markRead`/`setRead` — onvoorwaardelijk schrijft en waarschuwt. Dat is bestaand, verdedigbaar gedrag (een toggle wijzigt per definitie iets), maar het is een asymmetrie die je wilt vastpinnen voordat iemand 'm gladstrijkt.
- **Het randgeval:** een `ReadStore` waarop `load()` níét is aangeroepen. `_prefs` is dan `null` en alle schrijfacties zijn null-safe, dus de status verandert wel in het geheugen maar er wordt niets bewaard. Assert dat `isRead(id)` `true` is terwijl `prefs.getStringList('read_ids')` `null` blijft. In de echte app roept `main.dart:15` netjes `await readStore.load()` aan, dus dit is vandaag geen bug — het is een stilzwijgende afspraak die nu nergens vastligt.

### 2. `frontend-reader/test/time_format_test.dart`

Tests op `lib/time_format.dart` (`formatRelativeTime`, een pure functie van 14 regels, geen opzet nodig). Te dekken: lege invoer en onparseerbare invoer geven beide `''`; net nu → `zojuist`; 5 minuten → `5 min geleden`; 3 uur → `3 uur geleden`; **precies 3 dagen → `3 dagen geleden`** (de grens is `<= 3`, dus dit is de interessante kant); 10 dagen → een datum in de vorm `dd-mm-jjjj` met nul-padding op dag en maand. Bouw de invoer relatief aan `DateTime.now()`, want de functie gebruikt die zelf — hardgecodeerde datums geven een test die over een jaar iets anders betekent.

### Buiten scope, expliciet

Raak geen productiecode aan. `frontend-reader/lib/main.dart` (698 regels) is nadrukkelijk níét aan de beurt: die gebruikt globale singletons (`final ReadStore readStore = ReadStore()`, `final ApiClient api = ApiClient()`) en `ApiClient` roept `http.get` rechtstreeks aan, dus een widgettest op het enige scherm vraagt éérst een injectiepunt. Dat is een aparte, grotere stap. Ook buiten scope: `api_client.dart`, de deep-link-bestanden, de hoofdapp `frontend`, en de backend. Legt een test een echt verschil met het bedoelde gedrag bloot, meld dat dan in het worklog in plaats van de assertie aan te passen of het stilzwijgend te repareren — dat is stof voor een aparte story.

## Acceptance criteria

1. `frontend-reader/test/read_store_test.dart` bestaat en dekt alle zeven bovengenoemde gevallen, inclusief de teller-via-`addListener` voor de idempotentie-assertie en het `load()`-loze randgeval.
2. `frontend-reader/test/time_format_test.dart` bestaat en dekt alle zeven bovengenoemde gevallen, met invoer relatief aan `DateTime.now()` (geen hardgecodeerde datums).
3. `flutter test` in `frontend-reader/` is volledig groen; de twee bestaande tests in `widget_test.dart` zijn onaangeroerd en draaien mee (totaal ≥ 15 tests).
4. `git status` toont **alleen** de twee nieuwe testbestanden en het worklog als wijziging: geen bestand onder `frontend-reader/lib/`, geen `pubspec.yaml`, en geen `pubspec.lock` in de diff.
5. `flutter test --coverage` toont in `coverage/lcov.info` voor `lib/local_store.dart` en `lib/time_format.dart` **volledige regeldekking** (LH == LF, respectievelijk 32/32 en 10/10). Het worklog vermeldt de per-bestand-cijfers en het totaal over de drie gedekte bestanden.
6. De `coverage/`-map staat na afloop niet in de werkboom (opruimen na meting; hij is al gitignored via `frontend-reader/.gitignore:34`, dus dit is hygiëne).
7. Het worklog vermeldt expliciet of een test een verschil met het bedoelde gedrag blootlegde; zo ja, met de constatering en zónder de assertie of de productiecode aan te passen.

## Aannames

- **Coverage-ijking bijgesteld.** De storytekst noemde 49/66 regels over drie bestanden. Een refiner-probe met deze exacte opzet haalde **59/66** (`local_store.dart` 32/32, `time_format.dart` 10/10, `models.dart` 17/24, 15 tests groen in <1s). Het acceptatiecriterium is daarom geformuleerd op volledige dekking van de twee nieuwe doelbestanden, niet op het getal 49.
- **`pubspec.lock` niet meecommitten.** `flutter pub get` met de Flutter-versie in de agent-container (3.44.7) muteert `frontend-reader/pubspec.lock` echt (o.a. `boolean_selector` 1.4.0→1.4.1, `matcher` 0.12.17→0.12.19), terwijl CI 3.35.0 pint (`frontend-tests.yml`, `build-apk-reader.yml`). Die lockfile-wijziging hoort niet bij deze story en moet worden teruggedraaid vóór de commit.
- **Mock-isolatie.** `SharedPreferences.setMockInitialValues({...})` aan het begin van elke test is voldoende om lekken tussen tests te voorkomen; er is geen extra `setUp`/teardown of `SharedPreferences`-reset nodig. Geverifieerd door de probe: de "niets bewaard"-assertie (`getStringList('read_ids') == null`) blijft correct ook ná tests die wél schrijven.
- **Geen CI-wijziging nodig.** `.github/workflows/frontend-tests.yml` heeft al een aparte `frontend-reader`-job met paths-filter `frontend-reader/**` die `flutter pub get` + `flutter test` draait; de nieuwe tests worden daar automatisch opgepakt.
- **Bekende, niet mee te fixen ruwe kantjes** (constateringen voor een eventuele latere story, geen scope hier): `formatRelativeTime` geeft bij precies 1 dag `1 dagen geleden` (enkelvoud/meervoud), en de grenswaarden van `ReadStore` schrijven bij `toggleStar` ook als er niets zinvols wijzigt. De tests leggen het huidige gedrag vast zoals het is.

## Eindsamenvatting

**Eindsamenvatting SF-2200 — [Audit] Eerste echte tests voor de leesapp: ReadStore en formatRelativeTime**

**Wat is opgeleverd**
- `frontend-reader/test/read_store_test.dart` (8 tests) op `lib/local_store.dart`: `load()` met gevulde én lege opslag, idempotente `markRead`, `setRead(id, false)`, `markAllRead` met deels overlappende tweede aanroep, `markAllRead` zonder nieuwe id's, `toggleStar` heen en weer, en het randgeval waarin `load()` nooit is aangeroepen.
- `frontend-reader/test/time_format_test.dart` (7 tests) op `lib/time_format.dart`: lege en onparseerbare invoer, `zojuist`, minuten, uren, precies 3 dagen (de `<= 3`-grens) en 10 dagen als `dd-mm-jjjj` met nul-padding.
- Worklog `docs/stories/worklog/SF-2200-worklog.md`. Verder niets: de diff t.o.v. `main` bestaat uit exact deze drie bestanden.

**Gemaakte keuzes**
- Testopzet volgt het bestaande patroon uit `frontend/test/auth_logout_ws_test.dart`: `TestWidgetsFlutterBinding.ensureInitialized()` plus per test een eigen `SharedPreferences.setMockInitialValues({...})`. Geen nieuwe dependency, geen extra setUp/teardown.
- "Er is niets geschreven" wordt met twee middelen bewezen: een notificatieteller via `addListener` én een sentinelwaarde die vóór de no-op in SharedPreferences wordt gezet. Een assertie op alleen de verwachte eindwaarde zou ook slagen bij een overbodige herschrijving.
- Alle tijdsinvoer wordt relatief aan `DateTime.now()` opgebouwd (helper `isoAgo(Duration)`), zodat de tests over een jaar hetzelfde betekenen.
- De door `flutter pub get` gemuteerde `pubspec.lock` is bewust teruggedraaid: de agent-container draait Flutter 3.44.7 terwijl CI 3.35.0 pint.

**Wat is getest**
- `frontend-reader/`: `flutter test` → 17 tests groen (2 bestaande + 15 nieuwe), `flutter analyze` zonder issues. Onafhankelijk herdraaid door zowel reviewer als tester met hetzelfde resultaat.
- Regeldekking uit `coverage/lcov.info`: `local_store.dart` 32/32, `time_format.dart` 10/10, `models.dart` 17/24 (ongewijzigd) → 59/66 over de drie gedekte bestanden. De `coverage/`-map is na meting opgeruimd.
- Vangnet: `frontend/` 37 tests groen; backend `mvn clean verify` exit 0 (129 unit + 77 e2e, 0 failures).

**Bewust niet gedaan**
- Geen regel productiecode, `pubspec.yaml`, `pubspec.lock` of `widget_test.dart` aangeraakt. `main.dart`, `api_client.dart` en de deep-link-bestanden blijven ongetest — die vragen eerst een injectiepunt en zijn een aparte, grotere stap.
- Geen enkele test legde een echte afwijking van het bedoelde gedrag bloot. Drie bekende ruwe kantjes zijn vastgelegd in plaats van gerepareerd (stof voor een aparte story): `toggleStar` schrijft en waarschuwt onvoorwaardelijk, een `ReadStore` zonder `load()` bewaart niets terwijl de status in het geheugen wél wijzigt, en `formatRelativeTime` zegt bij precies 1 dag "1 dagen geleden".
- Geen browsertest: de leesapp heeft een vaste productieroute en dus geen per-PR preview, en zonder productiecodewijziging is er ook geen runtime-gedrag om te bewijzen.
