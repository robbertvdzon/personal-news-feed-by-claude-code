# SF-1953 — Voeg frontend-tests.yml toe en werk development.md bij

Onderdeel van story **SF-1952** — [Audit] Flutter-tests in CI draaien (frontend en frontend-reader).

De repo heeft 27 Flutter-tests (25 in `frontend/`, 2 in `frontend-reader/`) die door
geen enkele pipeline gedraaid werden. Frontend-wijzigingen gingen dus ongetest naar
`main`. Deze subtaak voegt het CI-vangnet toe en documenteert hoe je het lokaal draait.

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en de bestaande workflows gelezen
- [x] `.github/workflows/frontend-tests.yml` toegevoegd (twee jobs, één per app)
- [x] `docs/factory/development.md` aangevuld op beide genoemde plekken
- [x] YAML-geldigheid en -structuur geverifieerd met SnakeYAML
- [x] Beide Flutter-suites lokaal groen gedraaid (kopie in `/tmp`, geen lockfile-drift)
- [x] Vangnet `mvn -B --no-transfer-progress clean verify` groen
- [x] Zelfreview tegen de acceptance criteria

## Gedaan / waarom

### `.github/workflows/frontend-tests.yml` (nieuw)

- Kopcommentaar in de stijl van `backend-tests.yml`, inclusief de reden dat
  frontend-wijzigingen tot nu toe ongetest naar `main` gingen.
- Triggers `pull_request` en `push` naar `main`, beide met exact hetzelfde
  paths-filter: `frontend/**`, `frontend-reader/**` en
  `.github/workflows/frontend-tests.yml`. Wijzigingen die alleen
  `newsfeedbackend/**` of `docs/**` raken triggeren de workflow dus niet.
- `permissions: contents: read` — de workflow leest alleen.
- `env: FLUTTER_VERSION: '3.35.0'` op workflow-niveau, identiek aan
  `build-apk.yml:19` en `build-apk-reader.yml:19`, zodat CI-tests en de
  APK-builds niet uit elkaar lopen. De versie staat één keer in `env` en wordt
  niet inline per stap herhaald.
- **Twee losse jobs** (`frontend` en `frontend-reader`) in plaats van twee stappen
  in één job: zo blijft de uitslag van beide apps per run zichtbaar, ook als er
  één faalt (AC 6). Per job: `actions/checkout@v4` + `subosito/flutter-action@v2`
  (`channel: stable`, `cache: true`, versie uit `env`), daarna `flutter pub get`
  en `flutter test` met de bijbehorende `working-directory`.
- Géén `actions/setup-java` — `flutter test` draait op de Dart-VM en heeft geen
  Android-toolchain nodig. Dit wijkt bewust af van `build-apk.yml`.
- `timeout-minutes: 15` per job: ruim onder `backend-tests.yml` (30) en
  `build-apk.yml` (25), passend bij suites die lokaal in enkele seconden draaien.

### `docs/factory/development.md`

Bestaande tekst is aangevuld, niet vervangen:

- Blok `### Frontend (Flutter)` onder *Commands*: benoemt nu beide apps en bevat
  `flutter pub get` + `flutter test` voor `frontend/` en `frontend-reader/`, met
  een verwijzing naar `frontend-tests.yml` als CI-vangnet.
- Sectie `## Tests draaien`: de bestaande backend-tekst staat nu onder een kopje
  `### Backend`; daaronder een nieuw kopje `### Frontend (Flutter)` met dezelfde
  commando's, de vermelding dat CI dit via `frontend-tests.yml` afdwingt (analoog
  aan hoe de backend naar `mvn verify` verwijst), en een waarschuwing over
  lockfile-drift in `frontend-reader/pubspec.lock`.

## Verificatie

| Check | Resultaat |
| --- | --- |
| YAML-parse + structuur (SnakeYAML 2.5) | geldig; 2 jobs, 4 stappen elk, triggers/paths/env/permissions zoals gespecificeerd |
| `flutter test` in `frontend/` | 25/25 groen (~2 s) |
| `flutter test` in `frontend-reader/` | 2/2 groen |
| `mvn -B --no-transfer-progress clean verify` | groen (exitcode 0, 0 failures, 0 errors) |

De Flutter-suites zijn gedraaid op een kopie van beide apps in `/tmp`, zodat
`flutter pub get` de getrackte `pubspec.lock`-bestanden in de repo niet muteert
(AC 10). `git status` bevestigt dat de diff alleen uit de nieuwe workflow, deze
story-log, het worklog en `docs/factory/development.md` bestaat.

Lokaal draaide dit op Flutter 3.44.7 (de versie in de agent-container), terwijl CI
3.35.0 pint — die pin is bewust gelijkgehouden aan de APK-builds. Mocht een test in
CI op 3.35.0 alsnog rood zijn waar hij lokaal groen was, dan is dat een expliciete
melding waard en geen reden om de test aan te passen.

## Buiten scope gelaten

- `flutter analyze` toevoegen (geen testdekking, aparte afweging).
- De tests zelf uitbreiden of aanpassen.
- `.factory/verification.yaml` aanpassen — of de factory-runner een Flutter-binary
  heeft verschilt per omgeving.
- Productiecode (`frontend/lib/**`, `frontend-reader/lib/**`, backend).
- Historisch onjuiste "CI valideert dit alsnog"-conclusies in `docs/stories/**`;
  worklogs zijn een archief.
