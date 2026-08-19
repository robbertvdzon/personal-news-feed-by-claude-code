# SF-2200 - Worklog

Story-context bij eerste pickup:
Twee testbestanden voor ReadStore en formatRelativeTime

Voeg `frontend-reader/test/read_store_test.dart` en `frontend-reader/test/time_format_test.dart` toe. Raak geen productiecode, `pubspec.yaml`, `pubspec.lock` of `test/widget_test.dart` aan.

read_store_test.dart (tests op lib/local_store.dart): `TestWidgetsFlutterBinding.ensureInitialized()` bovenin `main()`, per test `SharedPreferences.setMockInitialValues({...})` - volg het patroon uit `frontend/test/auth_logout_ws_test.dart`. Dek zeven gevallen: (1) `load()` leest `read_ids` en `starred_ids` terug en lege opslag geeft lege sets; (2) `markRead(id)` is idempotent - tweede aanroep waarschuwt niet en schrijft niet, aangetoond met een teller via `addListener`; (3) `setRead(id, false)` verwijdert de markering, notificatie alleen bij echte wijziging; (4) `markAllRead(ids)` telt alleen nieuw-gezette items, tweede deels-overlappende aanroep telt het verschil; (5) een `markAllRead` zonder nieuwe id's geeft 0 zonder schrijfactie of notificatie; (6) `toggleStar(id)` heen en weer, met een commentaarregel die vastlegt dat deze methode - anders dan markRead/setRead - onvoorwaardelijk schrijft en waarschuwt; (7) randgeval zonder `load()`: `_prefs` is null, `isRead(id)` is true terwijl `prefs.getStringList('read_ids')` null blijft.

time_format_test.dart (tests op lib/time_format.dart): invoer relatief aan `DateTime.now()`, geen hardgecodeerde datums. Dek: lege invoer -> '', onparseerbare invoer -> '', net nu -> 'zojuist', 5 minuten -> '5 min geleden', 3 uur -> '3 uur geleden', precies 3 dagen -> '3 dagen geleden' (de `<= 3`-grens), 10 dagen -> datum in dd-mm-jjjj met nul-padding.

Verificatie: `flutter pub get` + `flutter test` in `frontend-reader/`, alles groen, totaal >= 15 tests. Daarna `flutter test --coverage`; noteer in het worklog de per-bestand-regeldekking uit `coverage/lcov.info` (verwacht local_store.dart 32/32, time_format.dart 10/10, models.dart 17/24 -> 59/66) en verwijder de `coverage/`-map daarna. Draai de door `flutter pub get` gemuteerde `pubspec.lock` terug (container heeft Flutter 3.44.7, CI pint 3.35.0) en controleer met `git status` dat alleen de twee nieuwe testbestanden en het worklog in de diff staan. Legt een test een echt verschil met het bedoelde gedrag bloot, meld dat in het worklog zonder de assertie of de productiecode aan te passen; de bekende ruwe kantjes ('1 dagen geleden' bij precies 1 dag, onvoorwaardelijke toggleStar) worden vastgelegd, niet gefixt. Sluit af met een eigen reviewstap over de diff.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `frontend-reader/test/read_store_test.dart` toegevoegd (8 tests) op `lib/local_store.dart`.
  `TestWidgetsFlutterBinding.ensureInitialized()` bovenin `main()`, per test een eigen
  `SharedPreferences.setMockInitialValues({...})` — hetzelfde patroon als
  `frontend/test/auth_logout_ws_test.dart`. Gedekt: `load()` (gevulde én lege opslag,
  bewust twee losse tests), idempotente `markRead`, `setRead(id, false)`, `markAllRead`
  met deels overlappende tweede aanroep, `markAllRead` zonder nieuwe id's, `toggleStar`
  heen en weer, en het randgeval zonder `load()`.
- Twee bewijsmiddelen voor "hier is niets gebeurd": een notificatieteller via `addListener`
  (aantoont dat er niet gewaarschuwd wordt) en een sentinelwaarde die vóór de no-op in
  SharedPreferences wordt gezet (`['sentinel']`). Blijft die staan, dan is er echt geen
  `setStringList` gedaan — een assertie op de verwachte waarde alleen zou ook slagen bij
  een overbodige herschrijving.
- `frontend-reader/test/time_format_test.dart` toegevoegd (7 tests) op `lib/time_format.dart`.
  Alle invoer wordt via de helper `isoAgo(Duration)` relatief aan `DateTime.now()` gebouwd,
  dus geen hardgecodeerde datums. De 10-dagen-verwachting wordt uit dezelfde `DateTime`
  afgeleid en daarnaast tegen `^\d{2}-\d{2}-\d{4}$` gelegd, zodat de nul-padding echt vastligt.
- Geen productiecode, `pubspec.yaml`, `pubspec.lock` of `test/widget_test.dart` aangeraakt.
  De door `flutter pub get` gemuteerde `frontend-reader/pubspec.lock` is teruggezet met
  `git checkout --` (container draait Flutter 3.44.7, CI pint 3.35.0).

Verschillen met het bedoelde gedrag:
- Geen enkele test legde een échte afwijking bloot; alle asserties beschrijven het huidige
  gedrag zoals het is. Twee bekende ruwe kantjes zijn bewust vastgelegd in plaats van gefixt
  (dat is stof voor een aparte story):
  - `toggleStar` schrijft en waarschuwt **onvoorwaardelijk**, anders dan `markRead`/`setRead`
    die alleen bij een echte wijziging schrijven. Vastgelegd met een commentaarblok in de test.
  - Een `ReadStore` waarop `load()` niet is aangeroepen heeft `_prefs == null`; alle
    schrijfacties zijn null-safe, dus de status wijzigt wel in het geheugen maar wordt niet
    bewaard. In de app roept `main.dart:15` netjes `await readStore.load()` aan, dus vandaag
    geen bug — wel een stilzwijgende afspraak die nu vastligt.
  - (Ook bekend en niet aangeraakt: `formatRelativeTime` geeft bij precies 1 dag
    `1 dagen geleden`; die grens valt buiten de gevraagde gevallen.)

Verificatie:
- `frontend-reader/`: `flutter pub get` + `flutter test` → **17 tests groen** (2 bestaande in
  `widget_test.dart` + 8 nieuw + 7 nieuw), < 1 s.
- `flutter test --coverage` → regeldekking uit `coverage/lcov.info`:
  - `lib/local_store.dart`: 32/32 (100%)
  - `lib/time_format.dart`: 10/10 (100%)
  - `lib/models.dart`: 17/24 (ongewijzigd)
  - totaal over de drie gedekte bestanden: **59/66**
  De `coverage/`-map is na de meting verwijderd.
- Vangnet volledig gedraaid: `frontend/` `flutter test` → 37 groen;
  `newsfeedbackend/newsfeedbackend` `mvn -B --no-transfer-progress clean verify` → exit 0,
  129 unit + 77 e2e, 0 failures/errors, 0 `[WARNING]`-regels, 4:34 min.
- `git status` toont alleen de twee nieuwe testbestanden en dit worklog.
