# SF-2130 - Worklog

Story-context bij eerste pickup:
Auth- en rolmodel corrigeren in specs/openapi.yaml

Doc-only wijziging in exact één bestand: specs/openapi.yaml. (1) Voeg aan components.schemas.AuthResponse een property `role` toe (type: string) met een description die benoemt dat de client hieruit het admin-onderscheid afleidt (Flutter auth_provider.dart leest het en maakt er isAdmin van, wat de zichtbaarheid van de Beheer-sectie bepaalt); voeg GEEN required-blok toe (schema heeft er nu geen en de Dart-client heeft een bewuste fallback naar 'user'). (2) Corrigeer AdminUserView.role van `example: ROLE_USER` naar `example: user` en SetRoleRequest.role van `example: ROLE_ADMIN` naar `example: admin`. (3) Geef alle drie de role-velden (AuthResponse.role, AdminUserView.role, SetRoleRequest.role) `enum: [user, admin]`. (4) Vul de inleidende beschrijvingszin (regel 7) aan zodat alle vijf de permitAll-paden uit SecurityConfig.kt:35 genoemd worden: /api/auth/**, /api/version, /api/shared/**, /ws/** en /actuator/**. BUITEN SCOPE: de ~18 overige ROLE_ADMIN-voorkomens in prozateksten (tags rond regel 54/56, endpoint-summaries en 403-descriptions in het blok 1022-1226) zijn Spring-Security-autoriteitsnamen en moeten letterlijk ongewijzigd blijven - doe dus geen brede zoek-en-vervang op ROLE_; ook buiten scope: /actuator/** documenteren onder paths:, en elke wijziging in newsfeedbackend/, frontend/, frontend-reader/ of andere docs/specs. Volg de bestaande YAML-indentatie en stijl van naburige schema's, herformatteer geen ongewijzigde regels. Er is geen unittest-werk: dit is een documentwijziging zonder runtime-gedrag. Sluit af met een zelfreview van de diff (grep op 'example: ROLE_' geeft nul treffers; git diff --name-only toont alleen specs/openapi.yaml naast het worklog) en werk het story-log docs/stories/worklog/SF-2130-worklog.md bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `specs/openapi.yaml` op vier punten in lijn gebracht met de implementatie (9 toegevoegde,
  3 gewijzigde regels, geen herformattering):
  1. `AuthResponse` heeft nu een property `role` (`type: string`, `enum: [user, admin]`,
     `description` die benoemt dat de client hier het admin-onderscheid uit afleidt). Bewust
     géén `required`-blok toegevoegd: het schema kent er geen en `auth_provider.dart` heeft een
     expliciete backwards-compat-fallback naar `'user'`.
  2. `AdminUserView.role`: `example: ROLE_USER` → `example: user`;
     `SetRoleRequest.role`: `example: ROLE_ADMIN` → `example: admin` (in Kotlin is `ROLE_USER`
     de constantnaam, `"user"` de waarde — `auth/domain/User.kt:20-21`).
  3. Alle drie de role-velden hebben `enum: [user, admin]`, in de inline-flow-stijl die het
     bestand elders al gebruikt (regels 1165, 1207, 1445, 1465, 1580).
  4. De inleidende zin noemt nu alle vijf permitAll-paden uit `SecurityConfig.kt:35`:
     `/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**` en `/actuator/**`.
- Buiten scope gelaten zoals gevraagd: de overige `ROLE_ADMIN`-vermeldingen in tags, summaries
  en 403-descriptions (Spring-Security-autoriteitsnamen, dus daar correct). Telling `ROLE_`:
  22 vóór → 20 ná, precies de twee gecorrigeerde `example:`-regels.

Verificatie:
- `grep -c 'example: ROLE_' specs/openapi.yaml` → 0.
- YAML-parse met SnakeYAML (wegwerp-Java-klasse in /tmp, opgeruimd): bestand parseert, top-level
  keys ongewijzigd (`openapi, info, servers, security, tags, paths, components`), de drie
  role-velden hebben exact `enum=[user, admin]` en `AuthResponse` heeft géén `required`.
- Vangnet `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`:
  BUILD SUCCESS, exit 0 — 116 unit-tests + 71 e2e-tests, 0 failures, 0 errors, geen
  `[WARNING]`-regels.
- Geen eigen unittests toegevoegd: de wijziging is doc-only zonder runtime-gedrag; er wordt in
  dit repo geen client uit `openapi.yaml` gegenereerd en geen CI-stap valideert het bestand.
- `git status` toont uitsluitend `specs/openapi.yaml` en dit worklog.
