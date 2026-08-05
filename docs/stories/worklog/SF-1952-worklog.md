# SF-1952 - Worklog

Story-context bij eerste pickup:
Voeg frontend-tests.yml toe en werk development.md bij

Maak .github/workflows/frontend-tests.yml naar model van backend-tests.yml: kopcommentaar in dezelfde stijl met de reden dat frontend-wijzigingen tot nu toe ongetest naar main gingen; triggers pull_request en push naar main, beide met paths-filter frontend/**, frontend-reader/** en .github/workflows/frontend-tests.yml; permissions contents: read; env FLUTTER_VERSION: '3.35.0' (gelijk aan build-apk.yml:18 en build-apk-reader.yml, niet inline per stap herhalen); twee losse jobs (een per app) zodat de uitslag van beide apps per run zichtbaar blijft ook als er een faalt; per job actions/checkout@v4 + subosito/flutter-action@v2 (channel: stable, cache: true, flutter-version uit env), daarna flutter pub get + flutter test met working-directory frontend respectievelijk frontend-reader; geen actions/setup-java (flutter test heeft geen Android-toolchain nodig); lage timeout-minutes. Werk daarnaast docs/factory/development.md bij op twee plekken: het blok '### Frontend (Flutter)' (nu r39-49, noemt alleen frontend/ en geen tests) en de sectie '## Tests draaien' (nu r71-96, nu puur backend) - voeg de concrete flutter test-commando's voor beide apps toe en vermeld dat CI dit via frontend-tests.yml afdwingt; bestaande tekst aanvullen, niet vervangen. Verifieer zelf dat de YAML geldig is en dat beide suites groen zijn (kopieer de app naar /tmp voor flutter pub get, of draai pubspec.lock terug) - frontend/pubspec.lock en frontend-reader/pubspec.lock mogen NIET in de PR staan, net zomin als wijzigingen onder frontend/lib/**, frontend-reader/lib/**, de test-mappen of newsfeedbackend/**. Sluit af met een zelfreview tegen de acceptance criteria. Loopt een test in CI (Flutter 3.35.0) rood waar hij lokaal (3.44.7) groen was: expliciet melden, niet de test aanpassen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-1953 (developer) uitgevoerd; volledige toelichting staat in
  docs/stories/SF-1953-frontend-tests-workflow.md.
- Nieuw: .github/workflows/frontend-tests.yml — twee losse jobs (frontend en
  frontend-reader), triggers pull_request + push naar main met paths-filter op
  frontend/**, frontend-reader/** en de workflow zelf, permissions contents: read,
  env FLUTTER_VERSION '3.35.0' (gelijk aan beide build-apk-workflows),
  subosito/flutter-action@v2 met channel stable + cache true, per job
  flutter pub get + flutter test, geen setup-java, timeout-minutes 15.
- docs/factory/development.md aangevuld (niet vervangen) op beide plekken: het
  '### Frontend (Flutter)'-commandoblok en de sectie '## Tests draaien' (nu
  opgesplitst in '### Backend' en '### Frontend (Flutter)'), inclusief de
  vermelding dat CI dit via frontend-tests.yml afdwingt.
- Verificatie: YAML geparsed met SnakeYAML 2.5 (geldig, structuur zoals gespecificeerd);
  flutter test in frontend/ 25 groen en in frontend-reader/ 2 groen, gedraaid op een
  kopie in /tmp zodat geen enkele pubspec.lock in de PR terechtkomt;
  mvn -B --no-transfer-progress clean verify groen (exitcode 0, 0 failures/errors).
- Lokale Flutter-versie was 3.44.7, CI pint 3.35.0 (bewust gelijk aan de APK-builds).
  Geen afwijking waargenomen; zou CI toch rood zijn, dan is dat een melding waard en
  geen reden de tests aan te passen.

Review (SF-1953, reviewer):
- Volledige story-diff (git diff main...HEAD) beoordeeld: 4 bestanden, 242 regels,
  uitsluitend nieuwe workflow + docs/worklog. Geen productiecode, geen test-mappen,
  geen pubspec.lock, geen newsfeedbackend/** — AC 10 geverifieerd op de diff zelf.
- frontend-tests.yml onafhankelijk geparsed met SnakeYAML 2.5: geldige YAML; triggers
  pull_request + push[main] met identiek paths-filter (frontend/**, frontend-reader/**,
  de workflow zelf), permissions contents: read, env FLUTTER_VERSION '3.35.0' (gelijk
  aan build-apk.yml en build-apk-reader.yml), 2 jobs x 4 stappen met working-directory
  frontend resp. frontend-reader, geen setup-java, timeout-minutes 15. AC 1-8 groen.
- Testaantal onafhankelijk geteld: 25 tests in frontend/test, 2 in frontend-reader/test
  (= de 27 uit de story). Geen codegen/mocks nodig, dus 'flutter pub get + flutter test'
  volstaat. pubspec.yaml sdk ^3.9.0 past bij Flutter 3.35.0 (Dart 3.9.x).
- [suggestie] development.md r126-127: het 'Reader-app'-blok doet 'cd frontend-reader'
  direct na 'cd frontend'; het eerdere blok (r58) gebruikt correct 'cd ../frontend-reader'.
  Als geheel gekopieerd faalt het tweede blok. Niet blokkerend.
- [info] frontend/pubspec.lock pint 'sdks: dart: ">=3.10.0-0"', terwijl CI Flutter 3.35.0
  (Dart 3.9.x) draait; pub herresolvet die lockfile dan. Risico is niet nieuw — build-apk.yml
  draait al 'flutter pub get' in dezelfde map op dezelfde gepinde versie. Eerste plek om te
  kijken als de workflow op 3.35.0 onverwacht rood is.
