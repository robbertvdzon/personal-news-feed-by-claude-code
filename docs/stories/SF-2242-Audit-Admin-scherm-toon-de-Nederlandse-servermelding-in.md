# SF-2242 - [Audit] Admin-scherm: toon de Nederlandse servermelding in plaats van rauwe JSON

## Story

[Audit] Admin-scherm: toon de Nederlandse servermelding in plaats van rauwe JSON

<!-- refined-by-factory -->

## Scope

Frontend-only. Het admin-scherm van de hoofd-app (`frontend/`) toont bij een mislukte beheeractie de rauwe responsebody van de backend in plaats van de Nederlandse servermelding.

Vandaag doet `frontend/lib/screens/admin_screen.dart:179` in `_handleAction`:

    } on ApiException catch (e) {
      if (context.mounted) _snack(context, 'Fout: ${e.statusCode} ${e.body}');

Daardoor ziet een admin letterlijk `Fout: 400 {"error":"Je kunt jezelf niet verwijderen"}`.

`docs/factory/technical-spec.md:93` legt als codeconventie vast dat backend-foutbodies altijd de vorm `{"error": "…"}` hebben en dat frontend-code die een servermelding toont het `error`-veld leest via de gedeelde helper `extractDutchMessage` (`frontend/lib/api/api_client.dart:18`), met terugval op de rauwe body. Die helper wordt gebruikt in `categories_screen.dart:79`, `rss_feeds_screen.dart:112` en `:228`; `admin_screen.dart:179` is de enige plek in beide Flutter-apps die het niet doet (`frontend-reader` kent geen `ApiException`).

Op te leveren:

1. **`frontend/lib/screens/admin_screen.dart`** — vervang in de `on ApiException`-tak van `_handleAction` de melding door `extractDutchMessage(e.body, emptyFallback: <Nederlandse fallback>)`, in de vorm van `rss_feeds_screen.dart:112` en `categories_screen.dart:79`, met een korte WHY-comment in dezelfde stijl. De statuscode verdwijnt uit de gebruikerszichtbare tekst. De generieke `catch (e)`-tak op :181 blijft ongewijzigd. `_snack` (:221) blijft ongewijzigd.
2. **Widgettest in `frontend/test/`** (nieuw bestand, bijv. `admin_screen_test.dart`) die vastlegt dat een `ApiException` met body `{"error":"Je kunt jezelf niet verwijderen"}` precies die tekst in de snackbar oplevert, en dat de rauwe JSON én de statuscode er niet in staan. Aanpak, naar het model van de bestaande tests: een subclass van `AdminUsersNotifier` die `build()` een vaste lijst laat teruggeven en `delete()` de `ApiException` laat gooien, via `adminUsersProvider.overrideWith(...)`, plus een `_FakeAuthNotifier` op `authProvider` zoals in `frontend/test/settings_screen_test.dart:14-49` (nodig voor de `isSelf`-bepaling). De test opent het `PopupMenuButton`, kiest "Verwijderen", bevestigt de dialoog met "Doorgaan" en pumpt daarna.
3. **`specs/frontend-spec.md`** — werk het blok "Beheer (alleen admins)" (§ rond regels 379-381, de AdminScreen-bullets) bij zodat er staat dat mislukte beheeracties een snackbar tonen met de Nederlandse servermelding uit het `error`-veld via de gedeelde helper, en niet de rauwe body of de statuscode.
4. **`docs/factory/technical-spec.md:93`** — vul de opsomming van helper-gebruikers aan met `admin_screen.dart`, zodat de conventieregel na deze wijziging weer klopt.

Buiten scope: `frontend/lib/api/api_client.dart` (de helper zelf), de backend, `specs/openapi.yaml`, `frontend-reader/`, de `error:`-tak van `usersAsync.when` op `admin_screen.dart:87` (toont een `AsyncError`, geen responsebody), en `admin_costs_screen.dart`.

## Acceptance criteria

1. `grep -n 'e.body' frontend/lib/screens/admin_screen.dart` toont alleen nog een regel waarin `e.body` als argument aan `extractDutchMessage` wordt doorgegeven; de string `'Fout: ${e.statusCode}'` komt niet meer voor in het bestand.
2. Bij een `ApiException` met body `{"error":"Je kunt jezelf niet verwijderen"}` toont de snackbar exact `Je kunt jezelf niet verwijderen` — zonder accolades, aanhalingstekens en zonder de statuscode.
3. Bij een `ApiException` met een lege body verschijnt de Nederlandse fallbacktekst; bij een body zonder `error`-veld verschijnt de body zelf (bestaand helper-gedrag, ongewijzigd).
4. De generieke `catch (e)`-tak toont onveranderd `'Fout: $e'`.
5. Er is minimaal één nieuwe widgettest in `frontend/test/` die AC2 vastlegt, inclusief een negatieve assertie op de rauwe JSON én op de statuscode.
6. `cd frontend && flutter test` is groen met **minimaal 38 tests** (baseline gemeten op 2026-08-21: 37 groen).
7. `cd frontend && flutter analyze` geeft geen nieuwe waarschuwingen.
8. `specs/frontend-spec.md` beschrijft in het Beheer-blok de foutsnackbar met de Nederlandse servermelding; `docs/factory/technical-spec.md:93` noemt `admin_screen.dart` als gebruiker van de helper.
9. `git status` toont geen wijziging aan `frontend/pubspec.lock` (zie aanname 5).
10. Geen wijziging in `newsfeedbackend/`, `specs/openapi.yaml`, `frontend/lib/api/api_client.dart` of `frontend-reader/`.

## Aannames

1. **Geen statuscode-filter.** De bestaande callers extraheren alleen bij `statusCode == 400` omdat daar maar één faalpad bestaat. Het admin-scherm heeft meerdere faalpaden, dus hier wordt voor élke `ApiException` geëxtraheerd. Alle backend-fouten passeren `GlobalExceptionHandler` en hebben dus de `{"error": …}`-vorm.
2. **Gevolg daarvan:** de 404-meldingen van `AdminServiceImpl` (`"User not found: <naam>"`) zijn Engels en komen na deze wijziging als Engelse zin in beeld in plaats van als JSON-fragment. Dat is een verbetering t.o.v. vandaag; het vernederlandsen van die backend-teksten valt buiten deze story (geen backend-wijziging).
3. **Realisme van het faalpad.** De twee meldingen uit de motivering ("Je kunt je eigen admin-rol niet verwijderen", "Je kunt jezelf niet verwijderen") zijn via de UI vandaag onbereikbaar: `admin_screen.dart` verbergt de menu-items `delete` en `make_user` als `isSelf` waar is. De praktisch bereikbare fouten zijn een verouderde lijst (404 na verwijdering door een andere admin), een mislukte wachtwoordreset en 401/403/500. De widgettest drijft het faalpad daarom via een falende fake-notifier op een ánder account; de story dekt de weergave, niet de bereikbaarheid.
4. De fallbacktekst is een generieke Nederlandse zin in de stijl van de bestaande callers (bijv. "Actie mislukt"); de exacte woordkeuze is aan de developer.
5. `flutter pub get` in de agent-container (3.44.7) kan lockfiles muteren terwijl CI 3.35.0 pint; een lockfile-diff moet vóór de commit worden teruggedraaid. Gemeten: `frontend/pubspec.lock` bleef ongewijzigd.
6. Verificatie is lokaal te draaien met `cd frontend && flutter test`; CI pakt dit op via de bestaande Flutter-testjob.

## Eindsamenvatting

## Eindsamenvatting SF-2242 — Admin-scherm toont Nederlandse servermelding

**Wat er gebouwd is**

Bij een mislukte beheeractie in het admin-scherm van de hoofd-app toonde de snackbar de rauwe responsebody van de backend, bijvoorbeeld `Fout: 400 {"error":"Je kunt jezelf niet verwijderen"}`. De `on ApiException`-tak van `_handleAction` in `frontend/lib/screens/admin_screen.dart` gebruikt nu de al bestaande gedeelde helper `extractDutchMessage(e.body, emptyFallback: 'Actie mislukt')`, met een WHY-comment in dezelfde stijl als de feed- en categoriescherm-callers. De statuscode en het `Fout:`-voorvoegsel verdwijnen uit de gebruikerszichtbare tekst.

**Keuzes**

- **Geen 400-filter.** De bestaande callers extraheren alleen bij statuscode 400; het admin-scherm doet het voor élke `ApiException`, omdat een beheeractie op meerdere manieren kan falen (404/401/403/500) en alle backend-fouten via `GlobalExceptionHandler` dezelfde `{"error": …}`-vorm hebben. Dit is expliciet vastgelegd in `docs/factory/technical-spec.md`.
- **Bewust geaccepteerd gevolg:** de Engelse 404-teksten van de backend (`User not found: bob`) verschijnen nu als Engelse zin in plaats van als JSON-fragment. Dat is een verbetering t.o.v. de huidige situatie; het vernederlandsen ervan is backendwerk en viel buiten scope.
- Fallbacktekst bij een lege body: "Actie mislukt". Bij een body zonder `error`-veld blijft het bestaande helper-gedrag (body zelf) ongewijzigd.
- De generieke `catch (e)`-tak, `_snack` en de `error:`-tak van `usersAsync.when` zijn onaangeraakt.

**Documentatie**

`specs/frontend-spec.md` (blok "Beheer (alleen admins)") beschrijft nu de foutsnackbar; `docs/factory/technical-spec.md` noemt `admin_screen.dart` als gebruiker van de helper inclusief de reden voor het ontbrekende 400-filter.

**Wat er getest is**

- Nieuw `frontend/test/admin_screen_test.dart` met drie widgettests: JSON-body → exacte Nederlandse tekst (met negatieve asserties op de rauwe JSON, de statuscode én het `Fout:`-voorvoegsel), lege body → fallback, body zonder `error`-veld → body zelf. Mutatiecheck gedaan: met de oude regel terug vallen alle drie om.
- `flutter test` in `frontend/`: **40 groen** (baseline 37, AC vroeg ≥38), 0 failures. `flutter analyze`: 6 issues, alle pre-existing infos, geen in de gewijzigde bestanden.
- Volledig vangnet toch gedraaid hoewel de backend niet geraakt is: `mvn clean verify` exit 0, 142 unit + 77 e2e groen. `frontend-reader`: 18 groen, ongewijzigd.
- **Live bewijs op preview** (`pnf-pr-238`, draaiende revisie geverifieerd via `/api/version`): alle drie de foutbodies zijn met een gemockte serverrespons door de echte app-bundle gedreven; screenshots tonen `Je kunt jezelf niet verwijderen`, `Actie mislukt` en `User not found: bob` — zonder accolades, aanhalingstekens of statuscode. Reviewer akkoord, tester akkoord, geen blockers.

**Bewust niet gedaan**

Geen wijziging aan de helper zelf (`api_client.dart`), de backend, `specs/openapi.yaml`, `frontend-reader/` of `admin_costs_screen.dart`. Ook niet: het vernederlandsen van de Engelse backend-foutteksten en het bereikbaar maken van de "je kunt jezelf niet verwijderen"-paden via de UI (die menu-items zijn bij het eigen account verborgen) — de story dekt de weergave, niet de bereikbaarheid. `frontend/pubspec.lock` is bewust ongewijzigd gehouden i.v.m. de Flutter-versiepin in CI.
