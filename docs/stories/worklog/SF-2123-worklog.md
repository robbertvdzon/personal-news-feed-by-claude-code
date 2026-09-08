# SF-2123 - Worklog

Story-context bij eerste pickup:
Naam corrigeren naar EpisodeLookup.translationInProgress en vertaalflow-statussen één constante geven

Voer alle wijzigingen van SF-2123 in één keer uit; geen gedragswijziging, de UI reageert na afloop op exact dezelfde statussen en toont exact dezelfde labels.

1) Naamcorrectie op de drie levende plekken: `docs/factory/technical-spec.md:85`, `specs/frontend-spec.md:278` (blockquote r274-281) en de comment bij de gedeelde constante in `frontend/lib/models/models.dart:369`. Overal `EpisodeLookup.translationInProgress` in plaats van het niet-bestaande `Podcast.translationInProgress`. Voeg in de comment bij `kPodcastInProgressStatuses` één halve zin toe over wát die smallere lijst is: de statussen van de vertaalflow van één RSS-aflevering (`translatedPodcastStatus`), niet van de podcast zelf. Het verhaal in beide documenten blijft inhoudelijk hetzelfde. Laat de treffers in `docs/stories/SF-2066-*.md` en de worklogs bewust staan: historische verslagen, geen levende huisregels.

2) Voeg in `frontend/lib/models/models.dart` naast `kPodcastInProgressStatuses` een tweede top-level constante toe, bijv. `const kPodcastTranslationInProgressStatuses = <String>{'PENDING', 'TRANSLATING', 'TTS_GENERATING'}` (`Set<String>`, `k`-prefix conform conventie), met een comment die uitlegt dat dit bewust een deelverzameling van de gedeelde set is en waarom (een vertaling doorloopt nooit de generatie-statussen). Laat `EpisodeLookup.translationInProgress` (`models.dart:516-518`) die constante gebruiken; de `null`-check en de `contains`-semantiek blijven identiek, er staat geen kaal statusliteral meer in de getter. `_phaseLabel` in `frontend/lib/screens/rss_podcast_detail_screen.dart:707-718` blijft een `switch` met exact dezelfde cases, dezelfde labels en dezelfde `default: 'bezig…'`-tak, maar krijgt er een korte comment bij die naar de nieuwe constante verwijst, zodat wie een status toevoegt beide plekken ziet. Voeg geen ongebruikte import toe (een comment vraagt er geen).

3) Breid `frontend/test/podcast_in_progress_statuses_test.dart` uit met een test die (a) asserteert dat de nieuwe constante exact `{'PENDING', 'TRANSLATING', 'TTS_GENERATING'}` is en (b) dat hij een echte deelverzameling is van `kPodcastInProgressStatuses` (bijv. via `containsAll`/`every`). Punt (b) is de eigenlijke vangrail.

Buiten scope: `_statusLabel`-duplicatie, hoofdlettergebruik van labels, `feed_screen`/`rss_screen`-duplicatie, `frontend-reader`, backend, `specs/openapi.yaml` en database. Wijzigingen beperken zich tot `frontend/lib/models/models.dart`, `frontend/lib/screens/rss_podcast_detail_screen.dart`, `frontend/test/podcast_in_progress_statuses_test.dart`, `docs/factory/technical-spec.md`, `specs/frontend-spec.md` (plus de worklog).

Sluit af met een zelfreview van de volledige story-diff en draai in `frontend/`: `flutter analyze` (geen nieuwe waarschuwingen bovenop de 7 pre-existing infos) en `flutter test` (baseline 27 groen, mag alleen toenemen). Zet kale `pubspec.lock`-drift terug. Controleer met `grep -rn "Podcast.translationInProgress" frontend specs docs/factory` dat er nul treffers overblijven.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **Naamcorrectie (3 levende plekken).** `Podcast.translationInProgress` bestaat niet;
  de getter staat op `EpisodeLookup` en leest `translatedPodcastStatus`. Gecorrigeerd in
  `docs/factory/technical-spec.md` (huisregel), `specs/frontend-spec.md` (blockquote) en
  de comment bij `kPodcastInProgressStatuses` (`frontend/lib/models/models.dart`). In die
  comment staat nu ook wát de smallere lijst is: de statussen van de vertaalflow van één
  RSS-aflevering, niet van de podcast zelf. Treffers in `docs/stories/SF-2066-*.md` en
  oudere worklogs zijn bewust ongemoeid gelaten — historische verslagen, geen huisregels.
- **Tweede constante.** `const kPodcastTranslationInProgressStatuses = <String>{'PENDING',
  'TRANSLATING', 'TTS_GENERATING'}` staat nu direct onder de gedeelde set, met een comment
  die uitlegt dat het bewust een deelverzameling is (een vertaling doorloopt nooit de
  generatie-statussen) en waar hij gelezen wordt. `EpisodeLookup.translationInProgress`
  gebruikt hem; de `null`-check en de `contains`-semantiek zijn ongewijzigd, het kale
  statusliteral is weg. `_phaseLabel` in `rss_podcast_detail_screen.dart` blijft exact
  dezelfde `switch` (zelfde cases, labels en `default: 'bezig…'`) en kreeg alleen een
  doc-comment die naar de nieuwe constante verwijst; geen nieuwe import nodig.
- **Vangrail-test.** `frontend/test/podcast_in_progress_statuses_test.dart` heeft er twee
  tests bij: (a) de nieuwe constante is exact die drie statussen, en (b) hij is een echte
  deelverzameling van `kPodcastInProgressStatuses` (`containsAll` + strikt kleinere lengte).
  Punt (b) wordt rood zodra de twee lijsten uit elkaar lopen.
- **Geen gedragswijziging.** De set die `translationInProgress` matcht is identiek aan het
  oude literal, en alle labels zijn ongewijzigd.

Verificatie (deze run):
- `frontend`: `flutter analyze` → 7 issues, exact de bekende pre-existing infos (ws_client,
  feed_screen, podcast_detail_screen, rss_detail_screen, rss_screen 3x), geen nieuwe.
- `frontend`: `flutter test` → 29/29 groen (baseline 27, +2 nieuwe tests).
- `frontend-reader`: `flutter test` → 2/2 groen; de lockfile-drift is teruggezet
  (`git checkout -- frontend-reader/pubspec.lock`), `frontend/pubspec.lock` bleef schoon.
- backend: `mvn -B --no-transfer-progress clean verify` → BUILD SUCCESS, 71 e2e-tests,
  0 failures/errors (~4:12).
- `grep -rn "Podcast.translationInProgress" frontend specs docs/factory` → nul treffers.

Review (SF-2124, eerste ronde) — akkoord, geen blockers:
- Volledige story-diff `main...HEAD` beoordeeld (6 bestanden, binnen de scope van AC9;
  alleen `frontend/lib/models/models.dart`, `rss_podcast_detail_screen.dart`,
  `podcast_in_progress_statuses_test.dart`, `docs/factory/technical-spec.md`,
  `specs/frontend-spec.md` + deze worklog).
- AC1 zelf nagelopen: `grep -rn "Podcast\.translationInProgress" frontend specs docs/factory`
  → nul treffers; de drie levende plekken noemen nu `EpisodeLookup.translationInProgress`.
- AC4/AC8: het kale literal is weg; `const List.contains` → `Set.contains` op dezelfde drie
  waarden, dus identiek gedrag. AC5: `_phaseLabel` is byte-identiek (zelfde cases, labels en
  `default: 'bezig…'`), alleen een doc-comment toegevoegd; geen nieuwe import.
- Testbewijs: harness-blok `[FACTORY VERIFICATION EVIDENCE]` groen, `testedTreeSha`
  `3d291a6…` = tree van commit `f255630` (HEAD). Frontend valt niet onder dat vangnet en is
  daarom in de review gericht herdraaid: `flutter test` → 29/29 groen, `flutter analyze` →
  7 issues, exact de bekende pre-existing infos. Working tree schoon, geen `pubspec.lock`-drift.
- [info] `podcast_detail_screen.dart:26` noemt de vertaalstatussen nog in proza; dat bestand
  valt buiten AC9-scope en is dus terecht niet aangeraakt — eventueel iets voor een latere story.

Test (SF-2125, tester) — akkoord:
- Statisch/AC-check op HEAD (`4255c28`): AC1 `grep -rn "Podcast\.translationInProgress"` over
  `frontend/ specs/ docs/factory/` → nul treffers; AC2/AC3 comments aanwezig bij beide
  constantes; AC4 `EpisodeLookup.translationInProgress` gebruikt
  `kPodcastTranslationInProgressStatuses`, geen kaal literal meer; AC5 `_phaseLabel`-diff is
  uitsluitend een doc-comment (4 toegevoegde regels, geen enkele gewijzigde/verwijderde
  coderegel) — cases, labels en `default: 'bezig…'` ongewijzigd; AC9 diff raakt exact de vijf
  toegestane bestanden (+ worklog), AC8 `git diff main...HEAD` op `newsfeedbackend/`,
  `specs/openapi.yaml` en `frontend-reader/` is leeg.
- AC6/AC7 (kopie in /tmp, dus geen `pubspec.lock`-drift): `flutter test` → **29/29 groen**
  (baseline 27 + 2 nieuwe: exacte inhoud en `containsAll`-deelverzameling).
  `flutter analyze` op HEAD én op een schone `main`-worktree: beide 7 info-lints, na
  normalisatie van regelnummers **identiek** → geen nieuwe waarschuwingen.
- Gedragsbewijs op de preview (`https://pnf-pr-224.vdzonsoftware.nl`, pods draaien
  `sha-f255630` = de developer-commit; de reviewer-commit raakt alleen deze worklog).
  `/api/rss` en `/api/podcast-source/by-rss-item/*` gemockt met Playwright-routes, zodat elke
  `translatedPodcastStatus` op het RSS-podcast-detailscherm gerenderd kon worden:
  - `PENDING` → "🇳🇱 Bekijk vertaling — in wachtrij…" (met spinner)
  - `TRANSLATING` → "🇳🇱 Bekijk vertaling — vertalen…"
  - `TTS_GENERATING` → "🇳🇱 Bekijk vertaling — audio genereren…"
  - `DONE` → "🇳🇱 Bekijk vertaling" (geen fase-label)
  - `GENERATING_AUDIO` en `FAILED` → "🇳🇱 Vertaal & genereer Nederlandse podcast"
  Die laatste twee zijn de discriminator: `GENERATING_AUDIO` zit wél in
  `kPodcastInProgressStatuses` maar niet in de vertaalset, en valt dus terecht níet in de
  in-progress-tak. Labels 1-op-1 gelijk aan de `_phaseLabel`-switch → geen gedragswijziging.
  Screenshots: `/work/screenshots/001..009`.
- Inlogmodus: **fallback wegwerp-account** `tester_sf-2123` (UI-registratie + UI-login). De
  vaste test-user-creds waren niet leesbaar: `TESTER_USERNAME`/`TESTER_PASSWORD` zijn leeg en
  `oc get secret newsfeed-api-keys -n pnf-pr-224` geeft `Forbidden` voor de SA
  `system:serviceaccount:agent-access:claude-agent`. Opgeruimd: `DELETE /api/account/me` → 200,
  herlogin → 401. Geen DB-mutaties buiten dat account (alle testdata was client-side mock).
- Working tree schoon; geen code/test/infra gewijzigd, alleen deze worklog.
