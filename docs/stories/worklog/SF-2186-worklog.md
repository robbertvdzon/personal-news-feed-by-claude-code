# SF-2186 - Worklog

Story-context bij eerste pickup:
401 op changePassword, minLength op twee schema's, norm en checklist bijwerken

Documentatie-only, geen Kotlin/Dart/testwijziging. (1) specs/openapi.yaml: voeg op operationId changePassword (:964-983) een kaal '401'-blok toe, numeriek na het bestaande '400', met alleen een description die BEIDE bronnen benoemt en de gebruikerszichtbare NL-melding letterlijk overneemt, bijv. 'Huidig wachtwoord klopt niet, of de gebruiker uit het token bestaat niet meer'. Geen content, geen example, geen components/responses. Loop de vijf UnauthorizedException-throw-sites na: AuthServiceImpl.kt:43/:44 -> login (al gedekt op :140), :53/:55 -> changePassword (dit gat), SecurityHelpers.kt:8 -> generiek, valt bewust buiten (globaal security-blok). Geen andere operatie krijgt een '401'. (2) Zet minLength: 4 op ChangePasswordRequest.newPassword (:2083-2084) en ResetPasswordRequest.newPassword (:2115-2116), in dezelfde vorm als AuthRequest.password (:1329); currentPassword krijgt GEEN constraint, de code valideert alleen het nieuwe wachtwoord. (3) specs/backend-technical-spec.md §8, blok 'De regel gold maar voor een statuscode (SF-2179)' (~:564): vervang 'sloot daarmee deze auditreeks' en de vooruitwijzing 'voor de volgende statuscode' door de feitelijke stand - 404, 400, 409 en 401 zijn nagelopen, met per statuscode de verificatiegrep en de telling (NotFoundException 15 regels vs '404'=12; BadRequestException 13 vs '400'=10; ConflictException 4 vs '409'=2, klopte al; UnauthorizedException 6 vs '401'=2 na deze story, met SecurityHelpers.kt:8 als expliciete uitzondering). Vermeld dat de regelaantallen throw-sites plus de klassedeclaratie in common/Exceptions.kt zijn. De afgeleide regels uit het blok blijven ongewijzigd staan; het SF-2094-blok hoger in §8 blijft ongemoeid. (4) docs/onboarding-senior-developer.md:112: voeg 401 (deze story) toe aan de checklistregel die nu alleen 404/SF-2094 en 400/SF-2179 noemt, beknopt, geen kopie van §8. Sluit af met een eigen review van de diff. Let op de indentatie: niets parseert dit YAML-bestand, dus een syntaxfout wordt nergens gevangen. Verwacht resultaat: grep -c "'401'" = 2, grep -c 'minLength' = 3, grep -n 'components/responses' leeg, git diff --name-only toont exact de drie scope-bestanden plus het worklog.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-2187 (developer, 2026-08-17) — documentatie-only, drie bestanden:
  1. `specs/openapi.yaml`, `changePassword`: kaal `'401'`-blok toegevoegd, numeriek ná het
     bestaande `'400'`, alleen een `description` (geen `content`, geen `example`, geen
     `components/responses`). De tekst benoemt beide bronnen uit `AuthServiceImpl`
     (`:55` fout huidig wachtwoord — NL-melding letterlijk overgenomen — en `:53` gebruiker
     uit het token bestaat niet meer).
  2. `specs/openapi.yaml`: `minLength: 4` op `ChangePasswordRequest.newPassword` en
     `ResetPasswordRequest.newPassword`, gelijkvormig aan `AuthRequest.password`.
     `currentPassword` bewust ongemoeid: de code valideert alleen het nieuwe wachtwoord.
  3. `specs/backend-technical-spec.md` §8: het SF-2179-blok claimde dat de auditreeks
     gesloten was en wees vooruit "voor de volgende statuscode"; dat is vervangen door de
     feitelijke stand (404/400/409/401 nagelopen) met per statuscode de verificatiegrep en
     de telling, plus `SecurityHelpers.kt:8` als expliciete uitzondering. De afgeleide
     regels eronder staan ongewijzigd.
  4. `docs/onboarding-senior-developer.md`, review-checklist: 401 (SF-2187) toegevoegd aan
     de regel die alleen 404 (SF-2094) en 400 (SF-2179) noemde, beknopt.

Verificatie:
- `grep -c "'401'" specs/openapi.yaml` = 2 (was 1); `grep -c 'minLength' specs/openapi.yaml` = 3
  (was 1); `grep -n 'components/responses' specs/openapi.yaml` leeg.
- Greps in `newsfeedbackend/newsfeedbackend/src/main` opnieuw gemeten en kloppend met de
  norm-tekst: `NotFoundException(` 15, `BadRequestException(` 13, `ConflictException(` 4,
  `UnauthorizedException(` 6; contractkant `'404'` 12, `'400'` 10, `'409'` 2, `'401'` 2.
- `specs/openapi.yaml` geparseerd met SnakeYAML 2.5 uit `~/.m2` (wegwerp-Java-klasse in /tmp,
  daarna opgeruimd): parse OK — niets in de build doet dit, dus de indentatie is los gecheckt.
- Vangnet `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`:
  exit 0, 129 unit + 77 e2e, 0 failures / 0 errors, 4:23 min. Geen code gewijzigd, dus dit is
  puur een baseline-bevestiging.
- `git diff --name-only` toont exact de drie scope-bestanden; geen wijziging in
  `newsfeedbackend/`, `frontend/`, `frontend-reader/` of `e2e/`.

Review (SF-2187, reviewer, 2026-08-17) — akkoord, geen bevindingen:
- Testbewijs: `[FACTORY VERIFICATION EVIDENCE]` `backend-maven-verify` status=passed, exitCode=0;
  `testedTreeSha` `e92afde…` == `git rev-parse HEAD^{tree}` van de developercommit `2ef7fee`.
- AC1-4 zelf nagemeten: `'401'` = 2 (login + changePassword), `minLength` = 3,
  `grep -n 'components/responses'` leeg, `'404'` 12 / `'400'` 10 / `'409'` 2 en de vier
  exception-greps 15/13/4/6 kloppen met de norm-tekst in §8.
- `specs/openapi.yaml` opnieuw met SnakeYAML 2.5 geladen: parse OK; `changePassword` heeft
  responses `[200, 400, 401]`, het `'401'`-blok is kaal (alleen `description`, block scalar zoals
  de 52 andere multiline-descriptions in dit bestand) en `minLength: 4` staat op
  `ChangePasswordRequest.newPassword` en `ResetPasswordRequest.newPassword`, niet op
  `currentPassword`.
- Throw-sites herleid: `AuthServiceImpl.kt:43/:44` → `login`, `:53/:55` → `changePassword`,
  `SecurityHelpers.kt:8` bewust buiten scope; geen andere operatie mist een `'401'`.
- AC5-7: de afgeleide regels onder het §8-blok en het SF-2094-blok zijn ongewijzigd,
  `docs/factory/technical-spec.md` en `docs/stories/**` zijn ongemoeid, en de diff bevat exact
  de drie scope-bestanden plus dit worklog.
