# SF-2221 - [Audit] Maak de twee nieuwste huisregels waar: de ontbrekende karakteriseringstest en de storynummers die niet bestaan

## Story

Twee vorige week opgeschreven huisregels verwijzen naar iets dat niet bestaat.
Deze story maakt beide verwijzingen waar: één nieuwe test plus tekstwijzigingen.
Geen wijziging in `frontend-reader/lib/`, `frontend/lib/`, `newsfeedbackend/` of
in een deploy-manifest; geen gedragswijziging.

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en `docs/factory/technical-spec.md` gelezen
- [x] Deel 1 — karakteriseringstest voor precies één dag toegevoegd aan `frontend-reader/test/time_format_test.dart`
- [x] Deel 2 — twin-drift-comments bij de drie bestaande tests (`Duration.zero`, 5 minuten, precies 3 dagen)
- [x] Deel 3 — SF-2208 → SF-2207 (12 plekken) en SF-2187 → SF-2186 (7 plekken) in de levende documentatie
- [x] Deel 4 — testtelling in `docs/factory/development.md` op 18 respectievelijk 8 gezet
- [x] Vangnet gedraaid: `flutter test` in `frontend-reader/` (18) en `frontend/` (37), `mvn -B --no-transfer-progress clean test` (141)
- [x] Lockfile-drift teruggezet, eigen reviewslag over de volledige diff

## Wat is er gedaan en waarom

**Deel 1 — karakteriseringstest.** `docs/factory/technical-spec.md:96` eist dat
vastgepind gedrag een comment bij de test zelf krijgt. Voor `ReadStore.toggleStar`
gebeurde dat al; voor `formatRelativeTime` ontbrak de test op precies één dag.
De nieuwe test asserteert `'1 dagen geleden'` — de reader kent geen enkelvoudsvorm
(`lib/time_format.dart:11`). Het comment noemt het woord *karakteriseringstest*
letterlijk, verwijst naar het hoofd-app-antwoord `'1 dag geleden'`
(`frontend/lib/util/time_format.dart:29`) en zegt expliciet dat de assertie alleen
in dezelfde diff als de implementatie mag wijzigen. Bewust **niet gerepareerd**:
dat is stof voor een aparte story.

**Deel 2 — twin-drift zichtbaar gemaakt.** Drie bestaande tests leggen
reader-gedrag vast dat in de hoofd-app anders is (`'zojuist'` vs `'net binnen'`,
`'5 min geleden'` vs `'5 minuten geleden'`, grens `<= 3` vs `< 3`). Wie de twee
`time_format.dart`-varianten ooit samenvoegt, maakt ze alle drie rood; het comment
vertelt nu dat dat de bedoeling is. Bij de eerste staat er bovendien bij dat
`'zojuist'` in de hoofd-app het negatief-tijdsverschil-geval is
(`frontend/lib/util/time_format.dart:17`), een guard die de reader mist. Geen
enkele assertie is aangepast: `git diff` op dit bestand toont 26 toegevoegde en
0 verwijderde regels.

**Deel 3 — storynummers.** SF-2208 en SF-2187 zijn interne subtaaknummers; er
bestaat geen story, worklog of commit onder die naam. De levende documentatie
verwijst nu naar SF-2207 respectievelijk SF-2186. `specs/backend-technical-spec.md:588`
bevatte beide nummers. `docs/stories/SF-2186-*.md` en
`docs/stories/worklog/SF-2186-worklog.md` zijn bewust ongemoeid gelaten: daar is
SF-2187 een correcte verwijzing naar de eigen subtaak (historisch verslag, geen
levende documentatie). Daarom per bestand vervangen, geen repo-brede `sed`.

**Deel 4 — telling.** De reader heeft nu 18 tests, waarvan 8 in
`time_format_test.dart`.

## Verificatie

- `flutter test` in `frontend-reader/`: 18 groen (baseline 17)
- `flutter test` in `frontend/`: 37 groen
- `mvn -B --no-transfer-progress clean test`: BUILD SUCCESS, 141 tests, 0 failures, 0 errors
- `grep -rn "SF-2208" . --exclude-dir=.git --exclude-dir=stories --exclude=.task.md`: 0 treffers
- `grep -rn "SF-2187" . --exclude-dir=.git --exclude-dir=stories --exclude=.task.md`: 0 treffers;
  `docs/stories/` houdt zijn 4 bestaande treffers
- 12 nieuwe `SF-2207`-regels en 7 nieuwe `SF-2186`-regels; elke gewijzigde documentatieregel
  verschilt uitsluitend in het storynummer
- `frontend-reader/pubspec.lock` teruggezet (Flutter 3.44.7 in de container vs 3.35.0 in CI);
  geen `coverage/`-map achtergelaten
