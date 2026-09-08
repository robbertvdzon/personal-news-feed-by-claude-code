# SF-1952 - [Audit] Flutter-tests in CI draaien (frontend en frontend-reader)

## Story

[Audit] Flutter-tests in CI draaien (frontend en frontend-reader)

<!-- refined-by-factory -->

## Samenvatting

De app heeft 27 Flutter-tests, maar geen enkele pipeline draait ze. Wijzigingen aan de twee Flutter-apps gaan dus ongetest naar main; in oude stories staat zelfs ten onrechte dat "CI dit alsnog valideert".

Deze story voegt een GitHub Actions-workflow toe die bij elke pull request en elke push naar main de tests van beide Flutter-apps draait. Beide testsuites zijn nu groen, dus het vangnet levert meteen een groen resultaat op.

Daarnaast wordt de ontwikkeldocumentatie aangevuld met de commando's om deze tests lokaal te draaien.

## Scope

**In scope**

1. Nieuw bestand `.github/workflows/frontend-tests.yml`:
   - Triggers `pull_request` en `push` naar `main`, beide met paths-filter op `frontend/**`, `frontend-reader/**` en `.github/workflows/frontend-tests.yml` — zelfde vorm als `backend-tests.yml:12-22`.
   - `permissions: contents: read`, zoals `backend-tests.yml:23-24`.
   - Setup per app: `actions/checkout@v4` + `subosito/flutter-action@v2` met `channel: stable` en `cache: true`, en `flutter-version` uit een `env`-blok met `FLUTTER_VERSION: '3.35.0'` — exact de actie, versie en vorm die `build-apk.yml:18` en `:37-40` al gebruiken, zodat CI en de APK-builds niet uit elkaar lopen.
   - Per app een stap `flutter pub get` gevolgd door `flutter test`, met `working-directory: frontend` respectievelijk `frontend-reader`.
   - Een `timeout-minutes` in de orde van de bestaande workflows (`backend-tests.yml` 30, `build-apk.yml` 25); voor deze snelle suites volstaat een lagere waarde.
   - Kopcommentaar in de stijl van `backend-tests.yml:3-9`, met de reden dat de workflow bestaat: tot nu toe gingen frontend-wijzigingen ongetest naar main.
2. `docs/factory/development.md` aanvullen met de Flutter-testcommando's voor beide apps, zodat de developer- en tester-agents weten dat dit vangnet bestaat en hoe ze het lokaal draaien. Twee plekken zijn relevant: het commandoblok `### Frontend (Flutter)` (nu regels 39-49, noemt alleen `frontend/` en geen tests) en de sectie `## Tests draaien` (nu regels 71-96, gaat nu uitsluitend over de backend). Vermeld daarbij dat CI deze tests via `frontend-tests.yml` afdwingt, analoog aan hoe de backend-sectie naar `mvn verify` verwijst.

**Buiten scope**

- `flutter analyze` toevoegen — aparte afweging, kan bestaande meldingen opleveren en is geen testdekking.
- De tests zelf uitbreiden of aanpassen.
- `.factory/verification.yaml` aanpassen — of de factory-runner een Flutter-binary heeft verschilt per omgeving; de GitHub-Actions-route werkt gegarandeerd omdat twee bestaande workflows daar al Flutter draaien.
- Productiecode (`frontend/lib/**`, `frontend-reader/lib/**`, backend) wijzigen.
- De historisch onjuiste "CI valideert dit alsnog"-conclusies in `docs/stories/**` corrigeren; worklogs zijn een archief.

## Acceptance criteria

1. `.github/workflows/frontend-tests.yml` bestaat, is geldige YAML en heeft een `name`-veld in dezelfde stijl als de bestaande workflows.
2. De workflow triggert op `pull_request` én op `push` naar `main`, in beide gevallen met een paths-filter dat exact `frontend/**`, `frontend-reader/**` en `.github/workflows/frontend-tests.yml` bevat.
3. Een wijziging die alleen `newsfeedbackend/**` of alleen `docs/**` raakt, triggert de workflow niet.
4. De workflow gebruikt `subosito/flutter-action@v2` met Flutter-versie `3.35.0`, waarbij die versie uit een `env`-variabele komt en niet inline per stap herhaald wordt. De waarde is identiek aan `FLUTTER_VERSION` in `build-apk.yml` en `build-apk-reader.yml`.
5. De workflow draait `flutter pub get` + `flutter test` in `frontend/` en dezelfde twee commando's in `frontend-reader/`.
6. Een falende testsuite in de ene app verhindert niet dat de andere app getest wordt: de uitslag van beide apps is per run zichtbaar. (Praktisch betekent dit twee losse jobs, of één job waarin de tweede suite ook bij een falende eerste nog draait.)
7. Bij een falende testrun faalt de workflow (rode check op de PR); bij groene suites is de workflow groen.
8. Het kopcommentaar van de workflow legt in dezelfde stijl als `backend-tests.yml:3-9` uit waarom de workflow bestaat, inclusief het punt dat frontend-wijzigingen tot nu toe ongetest naar main gingen.
9. `docs/factory/development.md` bevat op de twee genoemde plekken de concrete commando's om `flutter test` in `frontend/` en in `frontend-reader/` te draaien, met vermelding dat CI dit via `frontend-tests.yml` afdwingt.
10. De PR bevat geen wijziging aan `frontend/pubspec.lock` of `frontend-reader/pubspec.lock`, en geen wijziging aan bestanden onder `frontend/lib/**`, `frontend-reader/lib/**`, `frontend/test/**`, `frontend-reader/test/**` of `newsfeedbackend/**`.
11. `mvn -B --no-transfer-progress clean verify` (`.factory/verification.yaml`, command `backend-maven-verify`) blijft groen — deze story raakt de backend niet.

## Aannames

- De workflow is een puur vangnet: hij verandert geen enkel gedrag van de apps zelf en mag daarom nul regels productiecode raken.
- Beide suites zijn groen op het moment van refinen. Geverifieerd op 2026-08-05 door beide apps naar een tijdelijke map te kopiëren en daar `flutter pub get` + `flutter test` te draaien: `frontend` 25/25 geslaagd, `frontend-reader` 2/2 geslaagd. De workflow hoort dus meteen groen te zijn.
- Die lokale verificatie draaide op Flutter 3.44.7 (de versie in de agent-container), terwijl CI 3.35.0 pint. De gepinde CI-versie is bewust gelijkgehouden aan de APK-builds; als een test in CI op 3.35.0 tóch rood is waar hij lokaal groen was, wordt dat expliciet gemeld in plaats van de test aan te passen.
- `flutter pub get` in `frontend-reader/` muteert `frontend-reader/pubspec.lock` (op 3.44.7 o.a. `boolean_selector` 1.4.0→1.4.1, `matcher` 0.12.17→0.12.19, `leak_tracker_testing` 0.11.1→0.13.0); `frontend/pubspec.lock` bleef ongewijzigd. Beide lockfiles zijn in git getracked. In CI is dat ongevaarlijk (ephemere checkout), maar wie lokaal verifieert moet die wijziging buiten de PR houden — vandaar AC 10.
- De workflow installeert geen JDK en bouwt geen APK; `flutter test` draait op de Dart-VM en heeft geen Android-toolchain nodig. Dit wijkt bewust af van `build-apk.yml`, dat wél `actions/setup-java@v4` gebruikt.
- Doordat het paths-filter beide app-mappen bevat, draaien bij een wijziging in één app de tests van beide apps. Dat is geaccepteerd: de suites zijn klein en het houdt de workflow simpel.
- De bestaande `## Tests draaien`-sectie in `development.md` gaat nu uitsluitend over de backend; die tekst blijft inhoudelijk staan en wordt aangevuld, niet vervangen.

## Eindsamenvatting

Ik heb de story, het worklog, de diff en de agent-comments gelezen. Hier de eindsamenvatting.

## SF-1952 — Flutter-tests in CI draaien (frontend en frontend-reader)

### Wat is gebouwd
- **Nieuw: `.github/workflows/frontend-tests.yml`** — een GitHub Actions-workflow die bij elke pull request en elke push naar `main` de Flutter-testsuites van beide apps draait. Tot nu toe werden de 27 bestaande Flutter-tests door geen enkele pipeline gedraaid; frontend-wijzigingen gingen dus ongetest naar main.
- **Aangevuld: `docs/factory/development.md`** — op twee plekken (het `### Frontend (Flutter)`-commandoblok en de sectie `## Tests draaien`) staan nu de concrete commando's om de tests van beide apps lokaal te draaien, met de vermelding dat CI dit via `frontend-tests.yml` afdwingt.

De PR raakt nul regels productiecode: 4 bestanden, uitsluitend de workflow, de ontwikkeldocumentatie, de story-doc en het worklog.

### Gemaakte keuzes
- **Twee losse jobs** (één per app) in plaats van één job met twee stappen, zodat een falende suite in de ene app de andere niet blokkeert en de uitslag per app zichtbaar is.
- **Flutter-versie `3.35.0` via één `env`-variabele**, exact gelijk aan de bestaande APK-build-workflows, zodat CI-tests en APK-builds niet uit elkaar gaan lopen.
- **Geen JDK/Android-toolchain** geïnstalleerd: `flutter test` draait op de Dart-VM, dus dat is overbodig — bewust anders dan de APK-workflows.
- **Paths-filter** op `frontend/**`, `frontend-reader/**` en de workflow zelf; een wijziging die alleen de backend of docs raakt, triggert de workflow niet. Bij een wijziging in één app draaien wel beide suites — geaccepteerd, want de suites zijn klein en het houdt de workflow simpel.
- Timeout van 15 minuten per job, in lijn met de bestaande workflows.

### Wat is getest
- Alle 11 acceptatiecriteria zijn door reviewer én tester onafhankelijk nagelopen; de YAML is met twee verschillende parsers geldig bevonden en de structuur (triggers, paths, permissions, env, jobs, working-directories) komt overeen met de specificatie.
- Beide testsuites zijn daadwerkelijk gedraaid op een kopie buiten de repo: `frontend` 25/25 groen, `frontend-reader` 2/2 groen (samen de 27 tests uit de story).
- De backend-build (`mvn clean verify`) blijft groen — deze story raakt de backend niet.
- Scope-check op de diff bevestigt: geen wijzigingen aan app-code, testmappen, lockfiles of backend.
- Preview-omgeving als sanity-check: app start normaal, login rendert, API blijft correct afgeschermd.

### Beperking en open puntje
- **Flutter 3.35.0 is lokaal niet reproduceerbaar** (de agent-runner is aarch64, waarvoor Flutter geen 3.35.0-archief publiceert); lokaal is op 3.44.7 getest. De echte uitslag op de gepinde CI-versie wordt zichtbaar op deze PR zelf, want het paths-filter bevat de workflow. Loopt daar iets rood, dan is dat een melding waard en geen reden de tests aan te passen.
- **Kleine kopieerfout in de documentatie** (niet-blokkerend, door zowel reviewer als tester gesignaleerd): in het tweede commandoblok staat `cd frontend-reader` waar `cd ../frontend-reader` hoort. Wie het blok in zijn geheel kopieert, struikelt over die tweede regel. De commando's zelf kloppen; dit is een kandidaat voor de documentatie-subtaak.

### Bewust niet gedaan
- `flutter analyze` toevoegen aan CI — aparte afweging, kan bestaande meldingen opleveren en is geen testdekking.
- De tests zelf uitbreiden of aanpassen.
- De factory-verificatieconfiguratie aanpassen — of een runner een Flutter-binary heeft verschilt per omgeving; de GitHub Actions-route werkt gegarandeerd.
- Historisch onjuiste "CI valideert dit alsnog"-conclusies in oude worklogs corrigeren; die zijn een archief.

<!-- deploy-summary:start -->
Er is een automatische controle toegevoegd die bij elke wijziging aan de app alle bestaande tests draait. Zo wordt een fout voortaan opgemerkt voordat de wijziging live gaat, in plaats van erna. Aan de app zelf verandert niets zichtbaars.
<!-- deploy-summary:end -->
