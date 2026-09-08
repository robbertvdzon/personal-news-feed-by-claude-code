# SF-2186 - [Audit] Documenteer de 401 van PUT /api/account/password in het API-contract

## Story

[Audit] Documenteer de 401 van PUT /api/account/password in het API-contract

<!-- refined-by-factory -->

## Scope

Documentatie-only. De backend, de Flutter-app en de tests blijven ongewijzigd — die doen al het juiste; alleen het contract en de norm lopen achter.

Te wijzigen bestanden (en géén andere):
- `specs/openapi.yaml`
- `specs/backend-technical-spec.md`
- `docs/onboarding-senior-developer.md`

**Achtergrond.** De huisregel in `specs/backend-technical-spec.md` §8 (en `docs/factory/technical-spec.md`) zegt: elke throw-site die vanaf een endpoint bereikbaar is, heeft de bijbehorende statuscode op die operatie in `openapi.yaml` — voor élke statuscode die de code zelf gooit, niet alleen 404. SF-2094 deed dat voor 404, SF-2179 voor 400. Voor 409 klopt het al (3 throw-sites, 2 `'409'`-blokken). Voor 401 is het nooit gedaan.

**Het gat.** `PUT /api/account/password` (`operationId: changePassword`, `specs/openapi.yaml:964-983`) declareert alleen een `'200'` en een `'400'`, terwijl `AuthServiceImpl.changePassword` twee keer een `UnauthorizedException` gooit:
- `AuthServiceImpl.kt:55` — `"Huidig wachtwoord klopt niet"` als het meegestuurde `currentPassword` niet klopt (bedrijfs-401: gebruiker is ingelogd, token is geldig).
- `AuthServiceImpl.kt:53` — `"Invalid credentials"` als de gebruiker uit het token niet meer bestaat (account elders verwijderd, token nog geldig).

Het gedrag ligt al vast in `AuthE2eTest.kt:69` (`assertEquals(401, geweigerd.status)`) en de Flutter-app bouwt er al een melding voor (`frontend/lib/screens/settings_screen.dart:256-259`). Het bestand heeft geen `components/responses`, geen gedeeld error-schema en geen algemene zin in `info.description`: wat niet per operatie staat, staat nergens.

## Acceptance criteria

1. `specs/openapi.yaml` krijgt op `changePassword` een `'401'`-blok in dezelfde kale vorm als de andere foutresponses in dit bestand: alleen een `description`, geen `components/responses`. `grep -n 'components/responses' specs/openapi.yaml` blijft leeg.
2. Die `description` benoemt **beide** bronnen (huisregel "heeft één response meer dan één bron, benoem ze dan allemaal", SF-2172/SF-2179) en neemt de gebruikerszichtbare Nederlandse melding **letterlijk** over (huisregel NL-meldingen, SF-2179). Bijvoorbeeld: `Huidig wachtwoord klopt niet, of de gebruiker uit het token bestaat niet meer`.
3. `grep -c "'401'" specs/openapi.yaml` gaat van **1** naar **2** (het bestaande blok staat op regel 140, `login`). Geen andere operatie krijgt een `'401'`: gemeten is `grep -rn "UnauthorizedException(" newsfeedbackend/newsfeedbackend/src/main` = **6 regels** = 5 throw-sites + de klassedeclaratie in `common/Exceptions.kt`; daarvan horen `AuthServiceImpl.kt:43`/`:44` bij `login` (al gedekt), `:53`/`:55` bij `changePassword` (dit criterium) en valt `SecurityHelpers.kt:8` (`"not authenticated"`) er bewust buiten — die wordt door het globale `security`-blok afgedekt.
4. De bijbehorende schema-asymmetrie gaat mee: `AuthServiceImpl.kt:30`, `:52` en `:78` bevatten drie letterlijk identieke regels `if (…length < 4) throw BadRequestException("Password must be at least 4 characters")`, maar alleen `AuthRequest.password` heeft `minLength: 4` (`specs/openapi.yaml:1329`, het enige voorkomen in het bestand). Zet `minLength: 4` ook op `ChangePasswordRequest.newPassword` (regel 2083-2084) en `ResetPasswordRequest.newPassword` (regel 2115-2116). `grep -c 'minLength' specs/openapi.yaml` gaat van 1 naar 3.
5. `specs/backend-technical-spec.md` §8, blok "De regel gold maar voor één statuscode (SF-2179)" (regel ~564), stelt nu dat SF-2179 "deze auditreeks sloot" en leidt daaruit regels af "voor de volgende statuscode". Vervang die framing door de feitelijke stand: 404, 400, 409 én 401 zijn nagelopen, met per statuscode de verificatiegrep en de telling:
   - `NotFoundException` → `grep -rn "NotFoundException(" newsfeedbackend/newsfeedbackend/src/main` = 15 regels (14 throw-sites + klassedeclaratie) tegenover `grep -c "'404'" specs/openapi.yaml` = 12;
   - `BadRequestException` → 13 regels (12 + klassedeclaratie) tegenover `'400'` = 10;
   - `ConflictException` → 4 regels (3 + klassedeclaratie) tegenover `'409'` = 2 (klopte al, zonder aparte story);
   - `UnauthorizedException` → 6 regels (5 + klassedeclaratie) tegenover `'401'` = 2 ná deze story, met de expliciete uitzondering `SecurityHelpers.kt:8` die door het globale `security`-blok wordt afgedekt.
   De afgeleide regels uit het SF-2179-blok (concrete oorzaak in de `description`, NL-melding letterlijk overnemen, meer dan één bron allemaal benoemen, `minimum`/`maximum` vervangt de foutcode niet, telling is nooit één-op-één) blijven staan; alleen de bewering dat de reeks gesloten is en de vooruitwijzing "voor de volgende statuscode" worden vervangen door de stand van zaken.
6. De checklist-regel in `docs/onboarding-senior-developer.md:112` ("API-wijziging? …") wordt in dezelfde diff bijgewerkt: die noemt nu alleen 404 (SF-2094) en 400 (SF-2179) als uitgevoerde controles en moet 401 (deze story) meenemen, in dezelfde beknopte checklist-stijl — geen kopie van de volledige §8-tekst.
7. Geen wijziging in `newsfeedbackend/`, `frontend/`, `frontend-reader/` of `e2e/`. `git diff --name-only` toont exact de drie bestanden uit Scope.

## Aannames

- **Plaatsing van het `'401'`-blok:** numeriek oplopend, dus ná het bestaande `'400'`-blok van `changePassword`. Dat is consistent met de SF-2179-stijlregel ("kaal blok met alleen een `description`") en met `login`, waar `'401'` direct op `'200'` volgt omdat er geen `'400'` is.
- **Taal:** de `description` is Nederlands, zoals alle andere foutresponses in dit bestand, ook waar de onderliggende Kotlin-melding Engels is (`"Invalid credentials"`). Alleen de gebruikerszichtbare melding wordt letterlijk overgenomen.
- **Geen `example:` en geen `content:`-blok** op de `'401'`; de bestaande foutresponses in dit bestand hebben dat ook niet.
- **`currentPassword` krijgt géén `minLength`:** de code valideert alleen de lengte van het nieuwe wachtwoord (`AuthServiceImpl.kt:52`), niet van `currentPassword`. Een constraint daarop zou een niet-bestaande afdwinging suggereren.
- **`AuthRequest.password` houdt zijn `minLength: 4`** ongewijzigd, ook al doet `login` geen lengtecontrole: dat schema wordt gedeeld met `register`, dat de check wél heeft.
- **Verificatie is grep- en leeswerk.** Niets in de build parseert `specs/openapi.yaml`: er staat geen validatiestap in `.github/workflows/` of `.factory/verification.yaml`, en `python3` in de agent-container heeft geen `pyyaml`. Een "geldige YAML"-criterium is dus niet met een script hard te maken; de controle is de diff plus de grep-tellingen uit AC3 en AC4. De backend-build hoeft niet te draaien omdat er geen Kotlin wijzigt.
- **Historische stories buiten scope:** verwijzingen naar dit onderwerp in `docs/stories/**` zijn afgesloten verslagen en worden niet herschreven. `docs/factory/technical-spec.md` bevat de huisregel in generieke vorm (zonder claim over welke statuscodes zijn nagelopen) en blijft daarom ongewijzigd.

## Eindsamenvatting

## Eindsamenvatting SF-2186 — 401 op `PUT /api/account/password` gedocumenteerd

**Wat is gebouwd** (documentatie-only, drie bestanden + worklog; geen Kotlin, Dart of testcode gewijzigd):

1. **`specs/openapi.yaml` — `changePassword`**: een kaal `'401'`-blok toegevoegd, numeriek ná het bestaande `'400'`, met alleen een `description` (geen `content`, geen `example`, geen `components/responses`). De tekst benoemt **beide** bronnen uit `AuthServiceImpl`: fout huidig wachtwoord (NL-melding "Huidig wachtwoord klopt niet" letterlijk overgenomen) en de gebruiker uit het token die niet meer bestaat.
2. **`specs/openapi.yaml` — schema's**: `minLength: 4` op `ChangePasswordRequest.newPassword` en `ResetPasswordRequest.newPassword`, gelijkvormig aan `AuthRequest.password`. `currentPassword` bewust ongemoeid — de code valideert alleen het nieuwe wachtwoord.
3. **`specs/backend-technical-spec.md` §8**: het SF-2179-blok claimde dat de auditreeks "gesloten" was en wees vooruit "naar de volgende statuscode". Dat is vervangen door de feitelijke stand — 404, 400, 409 én 401 zijn nagelopen — met per statuscode de verificatiegrep en de telling, en `SecurityHelpers.kt:8` als expliciete uitzondering. De afgeleide regels eronder staan ongewijzigd.
4. **`docs/onboarding-senior-developer.md`**: de review-checklistregel noemt nu ook 401 (SF-2187), beknopt, zonder de §8-tekst te kopiëren.

**Gemaakte keuzes**: 401-blok in dezelfde kale stijl als de rest van het bestand; Nederlandse `description` ook waar de Kotlin-melding Engels is; geen `components/responses` geïntroduceerd; de generieke 401 uit `SecurityHelpers` blijft ongedocumenteerd omdat die door het globale `security`-blok wordt afgedekt (de preview geeft daar in de praktijk 403).

**Wat is getest**:
- Statische ACs op de branchrevisie: `'401'` = 2, `minLength` = 3, `components/responses` leeg; exception-greps 15/13/4/6 tegenover `'404'` 12 / `'400'` 10 / `'409'` 2 / `'401'` 2 — precies wat §8 nu claimt.
- YAML apart geparseerd (SnakeYAML / js-yaml) omdat niets in de build dit bestand leest. Semantische boomdiff `main` vs branch: exact drie toevoegingen en niets anders; 46 paths en 33 schemas ongewijzigd.
- Live gedragsbewijs op de preview met een wegwerp-account: fout `currentPassword` → **401** `{"error":"Huidig wachtwoord klopt niet"}`, correct wachtwoord → 200, nieuw wachtwoord van 2 tekens → 400 "Password must be at least 4 characters", zonder token → 403. Account opgeruimd via `DELETE /api/account/me`.
- Vangnet `mvn clean verify`: exit 0, 129 unit + 77 e2e, 0 failures — baseline, want er is geen code gewijzigd.

**Bewust niet gedaan**: geen wijziging in `newsfeedbackend/`, `frontend/`, `frontend-reader/` of `e2e/`; geen `'401'` op andere operaties; geen `minLength` op `currentPassword`; `docs/factory/technical-spec.md` en historische `docs/stories/**` ongemoeid; geen browser-screenshots (geen frontend-diff); geen OpenAPI-validatiestap in de CI toegevoegd (buiten scope).
