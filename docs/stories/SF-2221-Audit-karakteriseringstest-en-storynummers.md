# SF-2221 - [Audit] Maak de twee nieuwste huisregels waar: de ontbrekende karakteriseringstest en de storynummers die niet bestaan

## Story

[Audit] Maak de twee nieuwste huisregels waar: de ontbrekende karakteriseringstest en de storynummers die niet bestaan

<!-- refined-by-factory -->

## Scope

Twee vorige week opgeschreven huisregels verwijzen naar iets dat niet bestaat. Deze story maakt beide verwijzingen waar. Het is één nieuwe test plus tekstwijzigingen; er verandert niets in `frontend-reader/lib/`, `frontend/lib/` of in de Kotlin-code, en er is geen gedragswijziging.

**Deel 1 — de ontbrekende karakteriseringstest toevoegen.**

`docs/factory/technical-spec.md:96` stelt dat SF-2200 twee asymmetrieën in de leesapp heeft vastgepind en dat zo'n vaststelling "in een comment bij de test zelf" hoort. Voor `ReadStore.toggleStar` klopt dat (`frontend-reader/test/read_store_test.dart:150-155`), voor `formatRelativeTime` niet: er is geen test op precies één dag.

Voeg aan `frontend-reader/test/time_format_test.dart` één test toe, in de stijl van de bestaande zeven en met de bestaande `isoAgo(Duration)`-helper:

- `formatRelativeTime(isoAgo(const Duration(days: 1)))` geeft `'1 dagen geleden'` (`frontend-reader/lib/time_format.dart:11` kent geen enkelvoudsvorm).
- Zet er een comment bij dat expliciet zegt dat dit een *karakteriseringstest* is: het gedrag is bekend onjuist, de hoofd-app geeft hier `'1 dag geleden'` (`frontend/lib/util/time_format.dart:29`), en de assertie mag niet "gecorrigeerd" worden zonder dat de implementatie mee verandert.

**Deel 2 — de drie bestaande tests markeren die twin-drift vastleggen.**

Drie van de zeven bestaande tests asserteren op reader-gedrag dat in de hoofd-app anders is. Wie ooit de twee `time_format.dart`-varianten samenvoegt, maakt ze alle drie rood zonder te weten dat dat de bedoeling is. Zet bij elk een comment met het hoofd-app-antwoord ernaast:

| Test | Reader | Hoofd-app |
|---|---|---|
| `time_format_test.dart:22-24` (`Duration.zero`) | `'zojuist'` (`lib/time_format.dart:8`) | `'net binnen'` (`frontend/lib/util/time_format.dart:18`) |
| `:26-29` (5 minuten) | `'5 min geleden'` (`:9`) | `'5 minuten geleden'` (`:21`) |
| `:36-41` (precies 3 dagen) | `'3 dagen geleden'`, grens `<= 3` (`:11`) | de absolute datum, grens `< 3` (`:27`) |

Verander de asserties **niet** — ze beschrijven correct wat de reader doet; voeg alleen de verwijzing toe. Noem in het comment bij de eerste ook dat `'zojuist'` in de hoofd-app iets ánders betekent: daar is het gereserveerd voor een negatief tijdsverschil (`frontend/lib/util/time_format.dart:17`), een guard die de reader mist. De bestaande toelichting bij de 3-dagen-test (`:37-38`, over de `<= 3`-grens) blijft staan en wordt aangevuld, niet vervangen.

**Deel 3 — de storynummers corrigeren die nergens op uitkomen.**

De levende documentatie tagt elke huisregel met het storynummer waar de afweging staat. Twee nummers bestaan niet: er is geen `docs/stories/SF-2208-*`, geen `SF-2187-*`, geen worklog en geen commit. Het zijn interne subtaaknummers; de stories heten SF-2207 en SF-2186.

Vervang **SF-2208 → SF-2207** op: `specs/backend-technical-spec.md:320`, `:321`, `:572`, `:582`, `:584`, `:588`; `specs/backend-functional-spec.md:65`; `specs/frontend-spec.md:81`; `docs/factory/technical-spec.md:100`; `docs/onboarding-senior-developer.md:113`; `docs/factory/agents/tester.md:58`; `e2e/scenarios/start-scenario.md:21`. (12 plekken.)

Vervang **SF-2187 → SF-2186** op: `specs/backend-technical-spec.md:567`, `:574`, `:588`; `specs/backend-functional-spec.md:82`, `:86`; `specs/frontend-spec.md:312`; `docs/onboarding-senior-developer.md:112`. (7 plekken.)

Let op: `specs/backend-technical-spec.md:588` bevat **beide** nummers in één regel en heeft dus twee vervangingen.

Laat `docs/stories/SF-2186-*.md` en `docs/stories/worklog/SF-2186-worklog.md` **ongemoeid**: daar is SF-2187 een correcte verwijzing naar de eigen subtaak. Dit is historisch verslag, geen levende documentatie.

**Deel 4 — de testtelling in de factory-docs bijwerken.**

`docs/factory/development.md:185` zegt "samen **17 tests**" en de tabelregel `:191` noemt 7 tests voor `time_format_test.dart`. Beide getallen worden 18 respectievelijk 8.

## Acceptance criteria

1. `frontend-reader/test/time_format_test.dart` bevat acht tests; de nieuwe test asserteert `formatRelativeTime(isoAgo(const Duration(days: 1))) == '1 dagen geleden'` en gebruikt de bestaande `isoAgo`-helper.
2. Bij die nieuwe test staat een comment dat het woord "karakteriseringstest" letterlijk bevat, het hoofd-app-antwoord `'1 dag geleden'` noemt, en expliciet zegt dat de assertie niet gecorrigeerd mag worden zonder implementatiewijziging.
3. De drie tests op `time_format_test.dart` voor `Duration.zero`, 5 minuten en precies 3 dagen hebben elk een comment met het afwijkende hoofd-app-antwoord (`'net binnen'`, `'5 minuten geleden'`, de absolute datum met grens `< 3`); bij de eerste staat bovendien dat `'zojuist'` in de hoofd-app het negatief-tijdsverschil-geval is.
4. Geen enkele bestaande assertie in `time_format_test.dart` is gewijzigd; `git diff` op dat bestand toont alleen toegevoegde regels.
5. `flutter test` in `frontend-reader/` is groen met **18 tests** (baseline gemeten op deze branch: 17 groen).
6. `flutter test` in `frontend/` en `mvn -B --no-transfer-progress clean test` in `newsfeedbackend/newsfeedbackend/` blijven groen (deze story raakt daar niets).
7. `grep -rn "SF-2208" . --exclude-dir=.git --exclude-dir=stories --exclude=.task.md` geeft nul treffers.
8. `grep -rn "SF-2187" . --exclude-dir=.git --exclude-dir=stories --exclude=.task.md` geeft nul treffers, terwijl `grep -rn "SF-2187" docs/stories/` de bestaande treffers (`SF-2186-Audit-…md`, `SF-2186-worklog.md`) ongewijzigd houdt.
9. Er zijn 12 nieuwe `SF-2207`-treffers en 7 nieuwe `SF-2186`-treffers bijgekomen op exact de in de Scope genoemde regels; de omringende zinnen zijn inhoudelijk ongewijzigd (alleen het nummer verandert).
10. `docs/factory/development.md` noemt 18 tests voor de reader en 8 voor `time_format_test.dart`; de andere twee tabelregels (2 en 8) blijven staan.
11. `git status` toont alleen de bedoelde wijzigingen: geen diff op `frontend-reader/pubspec.lock`, geen `coverage/`-map.

## Aannames

- Zuiver test- en documentatiewerk: er wordt geen regel in `frontend-reader/lib/`, `frontend/lib/`, `newsfeedbackend/` of in een deploy-manifest gewijzigd. De meervoudsfout `'1 dagen geleden'` en de `<= 3`/`< 3`-divergentie worden **vastgelegd, niet gerepareerd** — dat is conform de huisregel op `docs/factory/technical-spec.md:96` en stof voor een aparte story.
- Het samenvoegen van de twee `time_format.dart`-varianten valt buiten scope; deze story maakt zo'n samenvoeging alleen navigeerbaar.
- De vervangingen in deel 3 zijn puur tekstueel; er wordt geen zin herschreven, geen inhoudelijke claim aangepast en geen historisch storyverslag aangeraakt.
- `.task.md` en `docs/stories/worklog/SF-2221-worklog.md` bevatten de storytekst zelf en dus onvermijdelijk de oude nummers; ze vallen buiten de grep-AC's (vandaar de `--exclude`-vlaggen in AC 7 en 8).
- `flutter test` in `frontend-reader/` muteert in de container (Flutter 3.44.7) `pubspec.lock` terwijl CI 3.35.0 pint — die kale lockfile-drift wordt vóór het afronden teruggezet (`git checkout -- frontend-reader/pubspec.lock`), zoals `docs/factory/development.md:177-182` voorschrijft.
- De reader-tests worden in CI automatisch opgepakt door de bestaande job `flutter test (frontend-reader)` in `.github/workflows/frontend-tests.yml`; er is geen workflow-wijziging nodig.

## Eindsamenvatting

Alles is geverifieerd. Hier de eindsamenvatting.

## SF-2221 — Audit: karakteriseringstest en storynummers

**Wat is gebouwd**

Zuiver test- en documentatiewerk. Nul regels productiecode gewijzigd (`frontend-reader/lib/`, `frontend/lib/`, `newsfeedbackend/`, deploy-manifesten blijven ongemoeid), geen gedragswijziging.

1. **Karakteriseringstest toegevoegd** — `frontend-reader/test/time_format_test.dart` heeft nu een achtste test die vastlegt dat precies één dag `'1 dagen geleden'` oplevert. Het comment noemt het woord "karakteriseringstest" letterlijk, verwijst naar het hoofd-app-antwoord `'1 dag geleden'` en verbiedt de assertie los te "corrigeren" zonder implementatiewijziging — conform de huisregel op `docs/factory/technical-spec.md:96`.
2. **Twin-drift-comments** bij de drie bestaande tests (`Duration.zero`, 5 minuten, precies 3 dagen) met het afwijkende hoofd-app-antwoord ernaast. Bij de eerste staat er ook bij dat `'zojuist'` in de hoofd-app iets ánders betekent (het negatief-tijdsverschil-geval, een guard die de reader mist). Geen enkele bestaande assertie geraakt: 26 toegevoegde, 0 verwijderde regels.
3. **Storynummers gecorrigeerd** — SF-2208 → SF-2207 (12 plekken) en SF-2187 → SF-2186 (7 plekken) in de levende documentatie, inclusief de dubbele vervanging op `specs/backend-technical-spec.md:588`. Per bestand vervangen, geen repo-brede `sed`, zodat de historische verslagen in `docs/stories/SF-2186-*` hun correcte SF-2187-verwijzingen houden.
4. **Testtelling bijgewerkt** — `docs/factory/development.md`: 17 → 18 tests, tabelregel `time_format_test.dart` 7 → 8.

**Keuzes**

- De meervoudsfout `'1 dagen geleden'` en de `<= 3`/`< 3`-divergentie zijn **vastgelegd, niet gerepareerd**. Dat is bewust: de huisregel schrijft voor dat bekend-onjuist gedrag eerst wordt vastgepind. Repareren is stof voor een aparte story.
- Het samenvoegen van de twee `time_format.dart`-varianten viel buiten scope; deze story maakt zo'n samenvoeging alleen navigeerbaar door de verschillen op de plek van de test te documenteren.
- Deel 3 is puur tekstueel: er is geen zin herschreven en geen inhoudelijke claim aangepast.

**Wat is getest**

- `flutter test` in `frontend-reader/`: **18 groen** (baseline 17), inclusief de nieuwe test op naam.
- `flutter test` in `frontend/`: 37 groen. `flutter analyze` in `frontend-reader/`: geen issues.
- `mvn -B --no-transfer-progress clean test`: 141 tests, 0 failures, 0 errors, BUILD SUCCESS. Harnessbewijs `backend-maven-verify` is groen op precies deze tree.
- Alle 11 acceptatiecriteria zijn door de tester nagelopen, waaronder de greps (0 treffers op SF-2208 en SF-2187 buiten de historie) en de word-diff die bevestigt dat alleen het storynummer als token wijzigt. `git status` schoon na alle runs: geen `pubspec.lock`-drift, geen `coverage/`-map.

**Bewust niet gedaan**

Geen browsertest. De story wijzigt nul regels productiecode, en de leesapp heeft sowieso geen per-PR preview — `deploy/base/reader-route.yaml` pint de vaste productieroute. De preview `pnf-pr-236` is wel gecontroleerd als omgevingsbewijs (HTTP 200, `/api/version` op de juiste revisie).

**Aandachtspunt (niet-blokkerend)**

De reviewer merkte op dat "Karakteriseringstest" in het comment met hoofdletter aan het begin van een zin staat; een hoofdlettergevoelige grep op het exacte woord vindt hem daardoor niet. Inhoudelijk voldoet het criterium volledig.
