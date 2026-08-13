# SF-2130 - [Audit] Zet het auth- en rolmodel goed in het API-contract

## Story

[Audit] Zet het auth- en rolmodel goed in het API-contract

<!-- refined-by-factory -->

## Scope

Doc-only story. Er wijzigt exact één bestand: `specs/openapi.yaml`. Dat bestand is in `README.md:34` en `specs/README.md:50` aangewezen als source of truth voor de interface tussen backend en frontend, maar wijkt op vier punten af van de werkelijke implementatie. Er wordt géén productiecode, test of ander document gewijzigd — code, e2e-tests en Flutter-app zijn het in alle vier de gevallen al met elkaar eens.

**1. `role` toevoegen aan `AuthResponse` (rond regel 1303-1310)**

Het schema heeft nu alleen `token` en `username`. De backend stuurt daarnaast `role` mee bij zowel registratie als login (`auth/api/dto/AuthDtos.kt:5`, `AuthServiceImpl.kt:39` en `:46`). Voeg een property `role` toe van `type: string`, in dezelfde stijl als de bestaande properties (`token` heeft een `description`, `username` niet). Noteer in de `description` waarvoor de client het veld gebruikt: de Flutter-app leest het (`frontend/lib/providers/auth_provider.dart:43`) en leidt er `isAdmin` van af (`:15`), wat bepaalt of de Beheer-sectie in het instellingenscherm zichtbaar is (`frontend/lib/screens/settings_screen.dart:110`).

**2. De twee rol-voorbeelden corrigeren**

- `AdminUserView.role` (regel 2047): `example: ROLE_USER` → `example: user`
- `SetRoleRequest.role` (regel 2062): `example: ROLE_ADMIN` → `example: admin`

In Kotlin is `ROLE_USER` de naam van de constante en `"user"` de waarde (`auth/domain/User.kt:20-21`); in het contract is de naam als waarde terechtgekomen. Een client die het voorbeeld letterlijk overneemt bij `PUT /api/admin/users/{username}/role` krijgt een `400` (`AuthServiceImpl.setRole`, bewaakt door `AdminE2eTest.kt:153`), en `GET /api/admin/users` geeft `"user"`/`"admin"` terug (`AdminE2eTest.kt:135` en `:147`).

**3. `enum: [user, admin]` toevoegen aan de role-velden**

Bij `AdminUserView.role`, `SetRoleRequest.role` en het nieuwe `AuthResponse.role`. De backend accepteert en produceert niets anders: `setRole` weigert elke andere waarde expliciet met een `400`, en `role` kan alleen via registratie (`User.ROLE_USER`/`ROLE_ADMIN`) of via `setRole` gezet worden.

**4. De inleidende beschrijving op regel 7 aanvullen**

De zin noemt nu drie publieke paden (`/api/auth/**`, `/api/version`, `/ws/**`), terwijl `SecurityConfig.kt:35` er vijf op `permitAll` zet; `/api/shared/**` en `/actuator/**` ontbreken. Het bestand spreekt zichzelf daarmee tegen: bij de shared-endpoints staat al `security: []` (regels 994 en 1011).

**Expliciet buiten scope:** de 18 voorkomens van `ROLE_ADMIN` in prozateksten (tags op regels 54 en 56, endpoint-`summary`s en `403`-`description`s in het blok 1022-1226). Dat is de Spring-Security-autoriteitsnaam (`hasRole("ADMIN")`) en die formulering is daar correct; alleen de twee `example:`-waarden zijn fout. Ook buiten scope: `/actuator/**` als pad documenteren in `paths:` (het staat al beschreven in `specs/backend-technical-spec.md:229-231`), en elke wijziging aan code, tests of andere specs.

## Acceptance criteria

1. `components.schemas.AuthResponse` bevat een property `role` met `type: string` en een `description` die benoemt dat de client er het admin-onderscheid uit afleidt.
2. `grep -n 'example: ROLE_' specs/openapi.yaml` geeft nul treffers; `AdminUserView.role` heeft `example: user` en `SetRoleRequest.role` heeft `example: admin`.
3. Alle drie de role-velden (`AuthResponse.role`, `AdminUserView.role`, `SetRoleRequest.role`) hebben `enum: [user, admin]`.
4. De beschrijvingszin op regel 7 noemt alle vijf de paden uit `SecurityConfig.kt:35`: `/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**` en `/actuator/**`.
5. De resterende `ROLE_ADMIN`-voorkomens in tags, summaries en `403`-descriptions zijn ongewijzigd (tellen vóór en na blijven gelijk op de twee gecorrigeerde `example:`-regels na).
6. `git diff --name-only` toont uitsluitend `specs/openapi.yaml`; er is geen wijziging in `newsfeedbackend/`, `frontend/`, `frontend-reader/` of andere docs.
7. Het bestand blijft geldige YAML en de bestaande indentatie-/opmaakstijl van naburige schema's wordt gevolgd (geen herformattering van ongewijzigde regels).

## Aannames

- Het derde role-veld (`AuthResponse.role`) krijgt óók de `enum`, hoewel de oorspronkelijke tekst alleen "beide role-velden" noemt. Reden: de backend kan daar per constructie niets anders dan `user`/`admin` in zetten, en een uitzondering zou opnieuw drift introduceren.
- `AuthResponse.role` wordt géén `required`-veld: het schema kent nu helemaal geen `required`-blok en de Flutter-client heeft een bewuste backwards-compat-fallback naar `'user'` voor oudere backends (`auth_provider.dart`, comment boven regel 43). Een `required`-blok toevoegen zou een bredere contractwijziging zijn dan deze correctie.
- `specs/openapi.yaml` wordt door geen enkele CI-workflow of `.factory/verification.yaml`-stap gevalideerd; de acceptatiecriteria zijn daarom mechanisch met grep en `git diff` te controleren en vereisen geen nieuwe build- of validatiestap.
- Er is geen enum-consument die door de nieuwe `enum` breekt: er wordt in deze repo geen client uit `openapi.yaml` gegenereerd (de Flutter-modellen zijn handgeschreven).

## Eindsamenvatting

Alles gelezen: `.task.md`, de story-diff en het worklog (developer SF-2131 + tester SF-2132). Hieronder de eindsamenvatting.

## SF-2130 — Auth- en rolmodel goed in het API-contract

**Wat is gebouwd**
Doc-only correctie in één bestand, `specs/openapi.yaml` (het aangewezen source-of-truth-contract tussen backend en frontend). Vier afwijkingen ten opzichte van de werkelijke implementatie zijn weggewerkt:

1. `AuthResponse` heeft nu een property `role` (`type: string`) met een description die uitlegt dat de client hier het admin-onderscheid uit afleidt. De backend stuurde dit veld al mee bij registratie én login; het contract kende het niet.
2. De twee foute rol-voorbeelden gecorrigeerd: `AdminUserView.role` `ROLE_USER` → `user`, `SetRoleRequest.role` `ROLE_ADMIN` → `admin`. In Kotlin was `ROLE_USER` de constantnaam en `"user"` de waarde; de naam was per abuis als waarde in het contract beland. Een client die het oude voorbeeld letterlijk overnam kreeg een `400`.
3. Alle drie de role-velden hebben nu `enum: [user, admin]`, zodat het contract afdwingt wat de backend feitelijk accepteert en teruggeeft.
4. De inleidende beschrijving noemt nu alle vijf publieke paden uit `SecurityConfig.kt` (`/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**`, `/actuator/**`) in plaats van drie.

**Gemaakte keuzes**
- `AuthResponse.role` is bewust **niet** `required` gemaakt: het schema kent geen `required`-blok en de Flutter-client heeft een expliciete backwards-compat-fallback naar `'user'`. Dat `required` maken zou een bredere contractwijziging zijn dan deze correctie.
- Ook het nieuwe `AuthResponse.role` kreeg de `enum`, hoewel de oorspronkelijke vraag alleen de twee bestaande velden noemde — anders was er direct nieuwe drift ontstaan.
- Bestaande stijl gevolgd (inline-flow enum zoals elders in het bestand), geen herformattering van ongewijzigde regels.

**Wat is getest**
- Live op preview (`pnf-pr-225`, geverifieerd op de juiste revisie): `register` en `login` geven exact `[token, username, role]` met `role="user"` — 1-op-1 met het nieuwe schema. Alle vijf publieke paden zijn zonder token bereikbaar (`/api/version`, `/api/shared/feed`, `/actuator/health`, WebSocket, `/api/auth/*`), controle-endpoint `/api/settings` geeft correct 403.
- Rolwaarden geverifieerd tegen backendcode en bestaande e2e-tests: `setRole` weigert alles buiten `user`/`admin` met 400 — het oude voorbeeld zou dus daadwerkelijk gefaald hebben.
- Mechanische checks: `example: ROLE_` → 0 treffers; `ROLE_`-tellingen 22 → 20, exact de twee gecorrigeerde regels. YAML parseert geldig (openapi 3.1.0). Diff raakt uitsluitend `specs/openapi.yaml` plus het worklog.
- Vangnet-build `mvn clean verify`: BUILD SUCCESS, 116 unit- + 71 e2e-tests, 0 failures.
- Testdata opgeruimd (wegwerp-account verwijderd, herlogin geeft 401).

**Bewust niet gedaan**
- De ~18 overige `ROLE_ADMIN`-vermeldingen in tags, endpoint-summaries en 403-descriptions blijven ongewijzigd: dat zijn Spring-Security-autoriteitsnamen en daar correct.
- `/actuator/**` is niet als pad onder `paths:` gedocumenteerd (staat al in de technische spec).
- Geen wijziging in backend, frontend, tests of andere documenten — die waren in alle vier de gevallen al onderling consistent; alleen het contract liep achter.
- Geen nieuwe unittests of CI-validatiestap: de wijziging heeft geen runtime-gedrag en er wordt geen client uit dit bestand gegenereerd.
- Geen browser-screenshots: geen UI-wijziging, en het admin-pad is op preview niet te tonen zonder admin-account.
