# SF-2242 - Worklog

Story-context bij eerste pickup:
Admin-scherm: Nederlandse servermelding via extractDutchMessage + widgettest + docs

Frontend-only wijziging in de hoofd-app.

1. `frontend/lib/screens/admin_screen.dart`: vervang in de `on ApiException`-tak van `_handleAction` (r179) `'Fout: ${e.statusCode} ${e.body}'` door `extractDutchMessage(e.body, emptyFallback: <korte Nederlandse zin, bijv. 'Actie mislukt'>)`, in de vorm van `rss_feeds_screen.dart:112` en `categories_screen.dart:79`, met een korte WHY-comment in dezelfde stijl. BEWUST GEEN `statusCode == 400`-filter: dit scherm heeft meerdere faalpaden en alle backend-fouten passeren `GlobalExceptionHandler`, dus hebben de `{"error": …}`-vorm. Gevolg (accepteren, benoemen in de PR): de Engelse 404-teksten van `AdminServiceImpl` (`"User not found: <naam>"`) verschijnen als Engelse zin i.p.v. als JSON-fragment; vernederlandsen is backendwerk en valt buiten scope. De generieke `catch (e)`-tak (r181, `'Fout: $e'`), `_snack` (r221) en de `error:`-tak van `usersAsync.when` (r87) blijven ongewijzigd.

2. Nieuwe widgettest in `frontend/test/` (bijv. `admin_screen_test.dart`): een `ApiException` met body `{"error":"Je kunt jezelf niet verwijderen"}` levert exact die tekst in de snackbar op, met negatieve asserties op de rauwe JSON én op de statuscode. Aanpak naar model van de bestaande tests: subclass van `AdminUsersNotifier` die `build()` een vaste lijst laat teruggeven en de actie de `ApiException` laat gooien, via `adminUsersProvider.overrideWith(...)`, plus een `_FakeAuthNotifier` op `authProvider` (precedent: `frontend/test/settings_screen_test.dart:14-49`) omdat `isSelf` de ingelogde username nodig heeft. Drijf het faalpad op een ANDER account dan de ingelogde admin - de `delete`/`make_user`-menu-items zijn verborgen bij `isSelf`. UI-flow: `PopupMenuButton` openen, 'Verwijderen' kiezen, dialoog bevestigen met 'Doorgaan', dan pumpen. Zet in een comment bij de test dat dit de weergave dekt, niet de bereikbaarheid van de 400-meldingen.

3. `specs/frontend-spec.md`: werk het blok '### Beheer (alleen admins)' (AdminScreen-bullets, r379-381) bij zodat er staat dat een mislukte beheeractie een snackbar toont met de Nederlandse servermelding uit het `error`-veld via de gedeelde helper, en niet de rauwe body of de statuscode.

4. `docs/factory/technical-spec.md:93`: vul de opsomming van helper-gebruikers ('gebruikt door `rss_feeds_screen.dart` en `categories_screen.dart`') aan met `admin_screen.dart`, zodat de conventieregel weer klopt.

Buiten scope: `frontend/lib/api/api_client.dart`, `newsfeedbackend/`, `specs/openapi.yaml`, `frontend-reader/`, `admin_costs_screen.dart`.

Verificatie voor het afronden: `cd frontend && flutter test` (minimaal 38 groen; baseline 37) en `cd frontend && flutter analyze` zonder nieuwe waarschuwingen. Let op: de container draait Flutter 3.44.7 terwijl CI 3.35.0 pint - controleer `git status` en draai een eventuele wijziging aan `frontend/pubspec.lock` terug vóór de commit. Sluit af met een review-stap op de eigen diff.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `frontend/lib/screens/admin_screen.dart`: de `on ApiException`-tak van `_handleAction` toont nu
  `extractDutchMessage(e.body, emptyFallback: 'Actie mislukt')` in plaats van
  `'Fout: ${e.statusCode} ${e.body}'`, met een WHY-comment in de stijl van
  `rss_feeds_screen.dart`/`categories_screen.dart`. Bewust GEEN `statusCode == 400`-filter: dit
  scherm heeft meerdere faalpaden en alle backend-fouten passeren `GlobalExceptionHandler` en
  hebben dus de `{"error": ...}`-vorm. De generieke `catch (e)`-tak, `_snack` en de `error:`-tak
  van `usersAsync.when` zijn ongewijzigd.
- Gevolg dat we accepteren en benoemen: de Engelse 404-teksten van `AdminServiceImpl`
  (`"User not found: <naam>"`) verschijnen nu als Engelse zin in plaats van als JSON-fragment.
  Vernederlandsen is backendwerk en valt buiten deze story.
- Nieuw `frontend/test/admin_screen_test.dart` met drie widgettests: (1) body
  `{"error":"Je kunt jezelf niet verwijderen"}` levert exact die tekst in de snackbar op, met
  negatieve asserties op de rauwe JSON, op de statuscode en op het `Fout:`-voorvoegsel; (2) lege
  body toont de fallback `Actie mislukt`; (3) body zonder `error`-veld toont de body zelf. Fakes:
  een `AdminUsersNotifier`-subclass (vaste lijst uit `build()`, `delete()` gooit de
  `ApiException`) via `adminUsersProvider.overrideWith(...)` plus een `_FakeAuthNotifier` op
  `authProvider` voor de `isSelf`-bepaling. Het faalpad wordt op een ander account (`bob`)
  gedreven, want bij `isSelf` verbergt het scherm het `delete`-menu-item; dat staat ook als
  comment in de test.
- Mutatiecheck gedaan: met de oude regel teruggezet vallen alle drie de nieuwe tests om
  (`+0 -3`), daarna implementatie hersteld.
- `specs/frontend-spec.md` (blok "Beheer (alleen admins)") en `docs/factory/technical-spec.md`
  (conventieregel over `extractDutchMessage`) bijgewerkt; die laatste noemt nu ook waarom
  `admin_screen.dart` geen 400-filter heeft.

Verificatie (vangnet uit docs/factory/development.md, frontend-kant):
- `cd frontend && flutter test`: **40 tests groen** (baseline 37), 0 failures.
- `cd frontend && flutter analyze`: 6 issues, alle pre-existing infos (feed_screen.dart:189,
  podcast_detail_screen.dart:278, rss_detail_screen.dart:64, rss_screen.dart:67/78/228) —
  geen nieuwe waarschuwingen.
- `cd frontend-reader && flutter test`: 18 groen (ongewijzigd, niet geraakt door deze story).
- Backend niet geraakt (geen wijziging in `newsfeedbackend/`, `specs/openapi.yaml`,
  `frontend/lib/api/api_client.dart` of `frontend-reader/`), maar het vangnet is toch volledig
  gedraaid: `mvn -B --no-transfer-progress clean verify` exit 0, **142 unit + 77 e2e**, 0 failures,
  0 errors, 4:17 min; `grep -icE 'warning|deprecat|self-attach'` op de log = 1 (de bekende
  `[Podcast]`-logregel, geen regressie).
- `frontend/pubspec.lock` is ongewijzigd; de drift op `frontend-reader/pubspec.lock` (bijproduct
  van `flutter pub get` op Flutter 3.44.7 vs 3.35.0 in CI) is teruggezet met `git checkout`.

## Review (SF-2243, ronde 1)

- Reviewer heeft de volledige story-diff (`git diff main...HEAD`) beoordeeld: 5 bestanden, geen scope-overschrijding (`newsfeedbackend/`, `specs/openapi.yaml`, `frontend-reader/`, `frontend/lib/api/api_client.dart`, `frontend/pubspec.lock` alle ongewijzigd).
- Revisie-anker: `git rev-parse HEAD^{tree}` = `dbb1cf74` = `testedTreeSha` uit [FACTORY VERIFICATION EVIDENCE] (backend-maven-verify passed, exit 0).
- Zelf gedraaid (frontend valt niet onder het harnessbewijs): `flutter test` = **40 groen**, `flutter analyze` = 6 pre-existing infos (identiek aan baseline), `git status` schoon na afloop.
- Alle acceptatiecriteria 1-10 geverifieerd; geen blockers of bugs gevonden. Akkoord.

## Test (SF-2244, story-brede test)

Inlogmodus: **fallback wegwerp-account**. `TESTER_USERNAME`/`TESTER_PASSWORD` waren leeg en de
SF-`agent:local`-harness heeft geen leesrechten op `newsfeed-api-keys`, dus geregistreerd via de
Flutter-UI als `tester_sf-2244` en na afloop opgeruimd (`DELETE /api/account/me` = 200,
herlogin = 401).

Preview: `https://pnf-pr-238.vdzonsoftware.nl`, `GET /api/version` → sha `fa35ebc` = HEAD van
`ai/SF-2242` (de reviewer-commit draait, dus het bewijs geldt voor de te mergen revisie).

Gedragsbewijs in de browser (Playwright, viewport 420x900, screenshots in `/work/screenshots/`).
Het admin-scherm is op preview niet bereikbaar met een gewoon account, dus de `role`-waarde uit
de login-response is naar `admin` herschreven en `GET /api/admin/users` +
`DELETE /api/admin/users/bob` zijn met `page.route` gemockt. Echte app-bundle, echte
`ApiException`-afhandeling, alleen het serverantwoord is gestuurd:

| Foutbody van de server | HTTP | Snackbar in beeld | Screenshot |
| --- | --- | --- | --- |
| `{"error":"Je kunt jezelf niet verwijderen"}` | 400 | `Je kunt jezelf niet verwijderen` | `14-snackbar-json.png` |
| *(leeg)* | 500 | `Actie mislukt` | `14-snackbar-empty.png` |
| `User not found: bob` | 404 | `User not found: bob` | `14-snackbar-plain.png` |

In geen van de drie snackbars staat een accolade, een aanhalingsteken, de statuscode of het
`Fout:`-voorvoegsel — AC2 en AC3 zijn daarmee live bewezen, niet alleen in een widgettest.

Overige checks:
- `cd frontend && flutter test`: **exit 0, 40 tests groen, 0 failures** (AC6, baseline 37 → ≥38).
- `cd frontend && flutter analyze`: 6 issues, alle pre-existing infos in `feed_screen.dart:189`,
  `podcast_detail_screen.dart:278`, `rss_detail_screen.dart:64` en `rss_screen.dart:67/78/228` —
  geen enkele in `admin_screen.dart` of `admin_screen_test.dart` (AC7). Exit 1 is hier het normale
  gedrag van `flutter analyze` bij info-lints, geen regressie.
- AC1: `grep -n 'e\.body' frontend/lib/screens/admin_screen.dart` geeft alleen r185
  (`extractDutchMessage(e.body, ...)`); `'Fout: ${e.statusCode}'` komt niet meer voor.
- AC4: de generieke `catch (e)`-tak op r188 toont onveranderd `'Fout: $e'`; ook `usersAsync.when`
  (r87) is ongewijzigd.
- AC9/AC10: `git status --porcelain` leeg na alle runs (dus geen `pubspec.lock`-drift); de diff
  raakt alleen de 5 bedoelde bestanden.
- Geen DB-mutatie op preview behalve het eigen wegwerp-account; geen productie aangeraakt.

Conclusie: geen bugs of blockers gevonden. Akkoord.
