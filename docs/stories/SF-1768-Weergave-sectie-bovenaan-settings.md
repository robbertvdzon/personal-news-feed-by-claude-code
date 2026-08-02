# SF-1768 — Weergave-sectie bovenaan settings_screen plaatsen

Subtaak: SF-1769 (development)

## Stappenplan

- [x] Issue-context, `docs/factory/development.md` en de bestaande code/tests lezen
- [x] Weergave-blok (kop + `SwitchListTile` "Grote tekst") naar de eerste positie van de `ListView` verplaatsen
- [x] Divider-boekhouding rechtzetten: scheiding ná Weergave, geen restant tussen Debug en Beheer
- [x] Widgettests in `frontend/test/settings_screen_test.dart` omdraaien naar de nieuwe volgorde
- [x] Extra tests op het aantal dividers (admin en niet-admin)
- [x] `flutter test` + `flutter analyze` in `frontend/`
- [x] Volledig vangnet `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend`

## Wat is gedaan en waarom

De sectie **Weergave** stond sinds SF-809 als laatste item ná de admin-only
`Beheer`-sectie. Deze story draait die keuze bewust terug: de "Grote tekst"-schakelaar
moet meteen zichtbaar zijn bij het openen van het instellingenscherm.

- `frontend/lib/screens/settings_screen.dart`: het blok `Text('Weergave')` +
  `SwitchListTile('Grote tekst')` is verplaatst naar de eerste positie van de `ListView`.
  De `Divider` die eerst vóór het blok stond is meeverhuisd naar *ná* het blok, zodat er
  geen scheidingslijn bovenaan het scherm belandt en er precies één scheiding tussen
  Weergave en "Over deze app" staat. Op de oude plek blijft niets achter: de lijst eindigt
  nu op de admin-only `Beheer`-sectie, in beide varianten zonder dubbele of losse `Divider`.
- De schakelaar zelf is ongewijzigd: waarde uit `appearanceProvider` (`largeFont`),
  wijziging via `setLarge(v)`. `TextScaler.linear(1.38)` in `main.dart` is niet aangeraakt.
- De overige sectievolgorde blijft: Over deze app → Account → Categorieën → RSS feeds →
  Achtergrond-taken → Opruimen → Debug → (admin-only) Beheer.

### Tests

`frontend/test/settings_screen_test.dart`:

- De twee positietests zijn omgedraaid: `weergaveDy` is nu *kleiner* dan de dy van
  "Over deze app", "Account", "Categorieën", "Debug" en — in de admin-variant — "Beheer".
  Testnamen en comments zijn meegewijzigd.
- Beide tests asserteren extra dat de onderlinge volgorde van de overige secties
  ongewijzigd blijft (en dat Beheer de laatste kop is).
- Twee nieuwe tests borgen de divider-boekhouding uit de acceptatiecriteria:
  7 `Divider`s in de niet-admin-variant, 8 in de admin-variant.
- De `setLarge`-test is inhoudelijk ongewijzigd gebleven.

### Verificatie

- `flutter test` in `frontend/`: 22 tests groen (was 20 — twee divider-tests erbij).
- `flutter analyze` in `frontend/`: 7 issues, allemaal pre-existing `info`-meldingen in
  andere bestanden (`ws_client.dart`, `feed_screen.dart`, `podcast_detail_screen.dart`,
  `rss_detail_screen.dart`, `rss_screen.dart`). Geen nieuwe warnings, niets in
  `settings_screen.dart`.
- Vangnet `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`:
  BUILD SUCCESS, exitcode 0, 0 failures / 0 errors (61 e2e-tests + unit-tests, ~3:54 min).
- `frontend/pubspec.lock` is niet gedrift (`git status` schoon voor lockfiles).
