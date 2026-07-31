# SF-1552 — RSS-feeds-editor gelijktrekken met de podcast-bronnen-editor

Subtaak: SF-1577 (development). Alleen `frontend/` + twee documentatiebestanden;
geen enkele wijziging onder `newsfeedbackend/`.

## Stappenplan

- [x] Issue, factory-docs (`development.md`, `technical-spec.md`) en de bestaande podcast-implementatie gelezen
- [x] `RssFeedsNotifier.save`: state pas muteren ná geslaagde PUT + cache-write (WHY-comment)
- [x] `_RssFeedsEditorState`: `_busy`-vlag + `Future<void> _save(...)` naar het model van de podcast-editor
- [x] Busy-affordances (veld disabled, verwijderknop disabled, spinner i.p.v. +-knop)
- [x] Message-extractie gedeeld gemaakt als private top-level functie, met de `"message"` → `"error"`-bugfix
- [x] Widget-tests uitgebreid met faal-paden (toevoegen, verwijderen, podcast) en een busy-state-test
- [x] `specs/frontend-spec.md` §9a en `e2e/scenarios/settings-scenario.md` §3a bijgewerkt
- [x] `flutter test` + `flutter analyze` gedraaid; vangnet (`mvn -B clean verify`) gedraaid

## Wat is er gedaan en waarom

**1. `frontend/lib/providers/data_providers.dart`** — `RssFeedsNotifier.save` zette eerst
`state = AsyncData(feeds)` en deed pas dáárna de PUT. Bij een 400 bleef de geweigerde URL
dus optimistisch in de UI staan. De volgorde is nu gelijk aan `PodcastFeedsNotifier.save`:
PUT → `LocalCache.saveObject` → `state`. Bij een fout blijven state én cache ongewijzigd en
propageert de `ApiException` naar de caller (WHY-comment toegevoegd).

**2/3. `frontend/lib/screens/rss_feeds_screen.dart`** — `_RssFeedsEditorState` heeft nu een
`_busy`-vlag en een `Future<void> _save(List<String> next, {String? validateFailureMessage})`
gemodelleerd naar `_PodcastFeedsEditorState._save`: `await` op de notifier, `_controller.clear()`
alleen bij een geslaagde toevoeg-actie, `try/catch` met `mounted`-guard en een rode snackbar
(`colorScheme.error`), en een `finally` die `_busy` terugzet. Zowel de verwijderknop als `_add()`
lopen via die methode; de onvoorwaardelijke `_controller.clear()` is verdwenen. Busy-affordances
zijn identiek aan podcast: `enabled: !_busy`, `onSubmitted`/verwijderknop uitgeschakeld en een
18×18 `CircularProgressIndicator(strokeWidth: 2)` op de plek van de +-knop.

**4. Message-extractie** — `_extractDutchMessage` is gepromoveerd tot een private top-level
functie die beide editors gebruiken, mét de bugfix: de regex zocht op het JSON-veld `"message"`
(Spring's `ResponseStatusException`-vorm), maar `GlobalExceptionHandler` in `common/Exceptions.kt`
geeft overal `{"error": "..."}`. Daardoor toonde de podcast-snackbar tot nu toe de rauwe JSON.
De raw-body-fallback blijft; de lege-body-fallback is meegeefbaar gemaakt (`emptyFallback`) zodat
hij voor beide editors passend is. De onjuiste doc-comment is herschreven.

**5. `frontend/test/rss_feeds_screen_test.dart`** — vier tests toegevoegd:
- toevoegen met HTTP 400 → snackbar toont de servertekst (niet de rauwe JSON), de URL komt niet
  in de lijst (gecheckt binnen `ListTile`, want hij hoort wél nog in het invoerveld te staan) en
  het invoerveld behoudt zijn tekst;
- verwijderen met HTTP 400 → feed blijft zichtbaar + snackbar;
- busy-state → tijdens een hangende save is de +-knop van de RSS-editor vervangen door een
  spinner en is het veld disabled; daarna is het veld weer leeg en enabled;
- podcast-faalpad → bewijst dat beide editors dezelfde `"error"`-extractie gebruiken.

**6/7. Documentatie** — `specs/frontend-spec.md` §9a beschrijft nu voor het RSS-blok dezelfde
server-side validatie/spinner/snackbar-verwachting als voor het podcast-blok (en het
podcast-blok is aangescherpt naar "rode snackbar met de tekst uit het `error`-veld").
`e2e/scenarios/settings-scenario.md` §3a heeft een stap gekregen met een door `SsrfUrlValidator`
geweigerde URL, plus de bijgewerkte regel in "Verwacht resultaat".

## Verificatie

- `cd frontend && flutter test` → **19 tests groen** (was 15).
- `cd frontend && flutter analyze` → 7 pre-existing info-meldingen, geen enkele in de gewijzigde
  bestanden; geen nieuwe meldingen.
- `cd newsfeedbackend/newsfeedbackend && mvn -B --no-transfer-progress clean verify` → gedraaid als
  vangnet (zie `.factory/verification.yaml`); resultaat staat in het worklog.
- `frontend/pubspec.lock` is niet gewijzigd (geen manifest-wijziging → geen lockfile-drift).
