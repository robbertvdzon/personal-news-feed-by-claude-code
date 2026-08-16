# SF-2179 - Worklog

Story-context bij eerste pickup:
Zeven '400'-blokken toevoegen in openapi.yaml en werkwijze aanvullen in backend-technical-spec.md

Doc-only wijziging in twee bestanden, geen productiecode en geen tests.

A. specs/openapi.yaml - voeg op zeven operaties een kaal '400'-blok toe met alleen een description, in de bestaande huisstijl (vgl. regel 116 'Wachtwoord te kort (minimaal 4 tekens)'). Werk op operationId, niet op regelnummer (regelnummers in de oorspronkelijke storytekst zijn 5 te laag en het bestand verschuift bij elke toevoeging). Plaats het blok op de bestaande conventie: statuscodes oplopend, dus '400' na '200'/'201' en vóór '403'/'404'. Descriptions in het Nederlands, ook waar de Kotlin-melding Engels is, en benoem de concrete oorzaak (nooit alleen 'Bad Request'):
1. changePassword (PUT /api/account/password) - nieuw wachtwoord korter dan 4 tekens.
2. resetUserPassword (PUT /api/admin/users/{username}/password) - idem.
3. setUserRole (PUT /api/admin/users/{username}/role) - BEIDE bronnen benoemen: rolwaarde is niet 'user' of 'admin', én een admin die zijn eigen adminrol intrekt; neem de melding 'Je kunt je eigen admin-rol niet verwijderen' letterlijk op.
4. deleteUser (DELETE /api/admin/users/{username}) - admin die zichzelf verwijdert; melding 'Je kunt jezelf niet verwijderen' letterlijk opnemen.
5. getDailyCosts (GET /api/admin/costs/daily) - days valt buiten 1..365.
6. getCostsByUser (GET /api/admin/costs/by-user) - onbekende period; toegestaan zijn this_month, last_month, this_year, all.
7. getExternalCalls (GET /api/admin/costs/calls) - from en/of to is geen geldige ISO-8601-timestamp (limit wordt geclamped en geeft géén 400).

Niet doen: geen components/responses introduceren; geen bestaande responses, schema's, parameters of prozateksten wijzigen; de vier al gedekte operaties (register, PUT /api/rss-feeds, PUT /api/podcast-feeds) niet aanraken. Let op: getDailyCosts heeft op de days-parameter al minimum: 1 / maximum: 365, maar Spring valideert dat niet - de 400 komt uit AdminCostsController.kt; het blok is dus wel nodig en de constraint blijft staan.

B. specs/backend-technical-spec.md - vul in §8, blok 'Foutcodes driften net zo stil (SF-2094)', het bullet 'Code → contract' aan met de 400-variant van de regel, in dezelfde schrijfstijl als de bestaande 404-regel ('N throw-sites + de klassedeclaratie in common/Exceptions.kt'), inclusief de verificatiegrep op BadRequestException, af te zetten tegen de dan 10 operaties met een '400' in openapi.yaml.

Let op bij verificatie: de backend-bronmap is newsfeedbackend/newsfeedbackend/src/main, niet 'src/main' zoals de AC's schrijven; de grep geeft 13 regels = 12 throw-sites + de klassedeclaratie in common/Exceptions.kt:15.

Sluit af met een zelfreview van de diff: alleen toevoegingen in openapi.yaml (14 regels bij enkelregelige descriptions), nul verwijderingen, correcte YAML-indentatie (kopieer die van een naburig '404'-blok), grep -c "'400'" specs/openapi.yaml geeft 10, grep -n 'components/responses' blijft leeg. Werk het story-log docs/stories/worklog/SF-2179-worklog.md bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- A. Zeven kale `'400'`-blokken toegevoegd in `specs/openapi.yaml`, telkens direct na het
  `'200'`-blok en vóór `'403'`/`'404'` (bestaande volgorde-conventie), met alleen een
  Nederlandse `description` die de concrete oorzaak benoemt:
  `changePassword`, `resetUserPassword` (wachtwoord < 4 tekens), `setUserRole` (beide
  bronnen: ongeldige rolwaarde én "Je kunt je eigen admin-rol niet verwijderen"),
  `deleteUser` ("Je kunt jezelf niet verwijderen"), `getDailyCosts` (`days` buiten 1..365),
  `getCostsByUser` (onbekende `period`), `getExternalCalls` (ongeldige ISO-8601 `from`/`to`;
  `limit` wordt geclamped). Geen `components/responses` geïntroduceerd; geen bestaande
  responses, schema's, parameters of prozateksten aangeraakt.
- B. `specs/backend-technical-spec.md` §8, bullet "Code → contract" aangevuld met de
  400-variant van de regel plus de verificatiegrep
  (`grep -rn "BadRequestException(" src/main` → 12 throw-sites + de klassedeclaratie in
  `common/Exceptions.kt`, af te zetten tegen de 10 operaties met een `'400'`), inclusief de
  reden dat de telling niet één-op-één is (`setUserRole` en `PUT /api/podcast-feeds` hebben
  elk twee throw-sites).

Verificatie:
- `grep -c "'400'" specs/openapi.yaml` → **10** (was 3); `grep -n 'components/responses'` → leeg.
- `git diff --numstat specs/openapi.yaml` → **16 toegevoegd, 0 verwijderd** (14 bij enkelregelige
  descriptions + 2 extra regels doordat de `setUserRole`-description een block scalar is, wat
  AC5 expliciet toestaat).
- YAML hard geparsed met SnakeYAML 2.5 uit `~/.m2` (wegwerp-Java-klasse in /tmp): bestand parseert,
  precies 10 operaties met een `400`-response, alle zeven nieuwe descriptions correct uitgelezen.
- Vangnet `mvn -B --no-transfer-progress clean verify`: **exit 0**, 129 unit + 77 e2e,
  0 failures / 0 errors, 4:28 min. `grep -icE 'warning|deprecat|self-attach'` op de verify-log
  geeft 1 hit — de bekende `[Podcast]`-logregel met het woord "warnings" erin, geen regressie.
  `AdminE2eTest` (10 tests) ongewijzigd groen.
