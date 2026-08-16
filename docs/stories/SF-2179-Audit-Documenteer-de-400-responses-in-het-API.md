# SF-2179 - [Audit] Documenteer de 400-responses in het API-contract

## Story

[Audit] Documenteer de 400-responses in het API-contract

<!-- refined-by-factory -->

## Scope

Doc-only wijziging in twee bestanden: `specs/openapi.yaml` en `specs/backend-technical-spec.md`. Geen productiecode, geen tests.

### Aanleiding

`specs/backend-technical-spec.md` §8 bevat sinds SF-2094 de huisregel "Code → contract": elke throw-site die vanaf een endpoint bereikbaar is, hoort een statuscode te hebben op de bijbehorende operatie. Die controle is voor `NotFoundException`/`'404'` volledig uitgevoerd (14 throw-sites tegenover 12 operaties met een `'404'`). Voor `BadRequestException`/`'400'` is hij nooit gedaan: er zijn 12 throw-sites in `src/main`, terwijl slechts 3 operaties een `'400'` declareren.

### A. Zeven `'400'`-blokken toevoegen in `specs/openapi.yaml`

Voeg op onderstaande zeven operaties een `'400'`-blok toe. Stijl exact gelijk aan de bestaande `'404'`- en `'400'`-blokken in dit bestand: een kaal blok met alleen een `description`. Introduceer géén gedeelde `components/responses` — die bestaat hier niet en wordt hier ook niet geïntroduceerd.

De `description` benoemt de concrete oorzaak, niet alleen "Bad Request":

| # | Operatie (`operationId`) | Bron van de 400 |
|---|---|---|
| 1 | `changePassword` — `PUT /api/account/password` | nieuw wachtwoord korter dan 4 tekens (`AuthServiceImpl.kt:52`) |
| 2 | `resetUserPassword` — `PUT /api/admin/users/{username}/password` | idem (`AuthServiceImpl.kt:78`) |
| 3 | `setUserRole` — `PUT /api/admin/users/{username}/role` | **twee** bronnen: rolwaarde is niet `user` of `admin` (`AuthServiceImpl.kt:85`), én een admin die zijn eigen adminrol probeert te verwijderen (`AdminServiceImpl.kt:42`, melding "Je kunt je eigen admin-rol niet verwijderen") |
| 4 | `deleteUser` — `DELETE /api/admin/users/{username}` | een admin die zichzelf probeert te verwijderen (`AdminServiceImpl.kt:49`, melding "Je kunt jezelf niet verwijderen") |
| 5 | `getDailyCosts` — `GET /api/admin/costs/daily` | `days` valt buiten 1..365 (`AdminCostsController.kt:25`) |
| 6 | `getCostsByUser` — `GET /api/admin/costs/by-user` | onbekende `period`; toegestaan zijn `this_month`, `last_month`, `this_year`, `all` (`AdminCostsController.kt:36`) |
| 7 | `getExternalCalls` — `GET /api/admin/costs/calls` | `from` en/of `to` is geen geldige ISO-8601-timestamp (`AdminCostsController.kt:63`, alleen die twee parameters worden geparsed; `limit` wordt geclamped en geeft geen 400) |

Bij 3 en 4 is de reden een gebruikerszichtbare Nederlandse foutmelding; neem die letterlijk op in de `description`. Dat is precies wat een clientbouwer nodig heeft om te weten dat hij er een scherm voor moet maken. Bij 3 hoort de `description` beide bronnen te benoemen, conform de SF-2172-regel voor een statuscode met meerdere bronnen.

### B. Werkwijze aanvullen in `specs/backend-technical-spec.md`

Vul in §8, in het blok "Foutcodes driften net zo stil (SF-2094)", het bullet "Code → contract" aan met de 400-variant van de regel, inclusief de verificatiegrep (`grep -rn "BadRequestException(" src/main` → 12 throw-sites + de klassedeclaratie in `common/Exceptions.kt`, af te zetten tegen de 10 operaties met een `'400'` in `openapi.yaml`), zodat de volgende wijziging hem meeneemt. Volg de bestaande schrijfstijl van dat bestand.

### Buiten scope

- Geen codewijziging, geen testwijziging.
- De vier reeds gedekte throw-sites niet aanraken: `AuthServiceImpl.kt:30` (`POST /api/auth/register`, `'400'` op openapi-regel 116), `SettingsServiceImpl.kt:57` (`PUT /api/rss-feeds`, regel 224), `SettingsServiceImpl.kt:70` en `PodcastFeedsServiceImpl.kt:36` (`PUT /api/podcast-feeds`, regel 277).
- Geen andere statuscodes, schema's of prozateksten wijzigen.
- Geen frontend-wijziging.

## Acceptance criteria

1. `grep -c "'400'" specs/openapi.yaml` geeft **10** (was 3).
2. Elk van de 12 `BadRequestException`-throw-sites in `src/main` is te herleiden tot een operatie die nu een `'400'` declareert. (`grep -rn 'BadRequestException(' src/main` geeft 13 regels: 12 throw-sites + de klassedeclaratie in `common/Exceptions.kt`.)
3. Elke nieuwe `description` benoemt de concrete oorzaak, niet alleen "Bad Request". Bij `setUserRole` staan beide bronnen erin; bij `setUserRole` en `deleteUser` staat de Nederlandse gebruikersmelding er letterlijk in.
4. De nieuwe `'400'`-blokken zijn kale blokken met alleen een `description`; `grep -n 'components/responses' specs/openapi.yaml` blijft leeg.
5. De bestaande `'200'`/`'201'`/`'403'`/`'404'`-blokken op deze zeven operaties zijn ongewijzigd; de diff op `specs/openapi.yaml` bestaat uitsluitend uit 14 toegevoegde regels (7× `'400':` + 7× `description:`, of meer regels bij een block-scalar `description`) en nul verwijderde regels.
6. `specs/backend-technical-spec.md` §8 beschrijft de 400-variant van de code-naar-contract-regel inclusief de verificatiegrep.
7. `mvn -B --no-transfer-progress clean verify` blijft groen (er wordt geen code geraakt). `AdminE2eTest.kt` assert vijf van deze zeven gevallen al (onbekende rol, eigen adminrol, zichzelf verwijderen, en `daily`/`by-user`/`calls`-parametervalidatie) en hoeft niet aangepast te worden.

## Aannames

1. **Twee bestanden, niet één.** De openingszin van het oorspronkelijke issue zei "één bestand", maar AC6 eist expliciet een aanvulling in `specs/backend-technical-spec.md`. AC6 is leidend: de werkwijze-aanvulling is juist het punt waarop deze auditreeks zichzelf beëindigt.
2. **Regelnummers in het oorspronkelijke issue waren 5 te laag.** De werkelijke pad-regels in `specs/openapi.yaml` zijn 964, 1054, 1079, 1104, 1140, 1166 en 1191. Ga uit van de `operationId`, niet van een regelnummer — het bestand verschuift bij elke toevoeging.
3. **Vier reeds gedekte throw-sites, niet vijf.** Het oorspronkelijke issue sprak van "vijf" maar somde er vier op. De telling klopt: 8 nieuw te dekken throw-sites over 7 operaties (`setUserRole` heeft er twee) + 4 al gedekt = 12.
4. **`GET /api/admin/costs/daily` heeft ondanks `minimum: 1` / `maximum: 365` op de `days`-parameter wél een `'400'` nodig.** Spring valideert die schema-grenzen niet; `AdminCostsController` doet de check zelf en gooit een `BadRequestException`. De parameter-constraint blijft ongewijzigd staan.
5. **Formulering-taal volgt het bestand.** De bestaande `'400'`-descriptions zijn Nederlands (bijv. "Wachtwoord te kort (minimaal 4 tekens)"); de nieuwe zijn dat ook, ook waar de onderliggende Kotlin-melding Engels is.
6. **Geen tooling valideert `specs/openapi.yaml`.** Er is geen CI-workflow of `.factory/verification.yaml`-stap die dit bestand parseert, en `python3` in de agent-container heeft geen `pyyaml`. Acceptatie is daarom grep- en diff-gebaseerd; de reviewer controleert de YAML-geldigheid met het oog op basis van de diff.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de volledige diff (`main...HEAD`) gelezen. Hier is de eindsamenvatting.

## Eindsamenvatting SF-2179 — [Audit] Documenteer de 400-responses in het API-contract

**Wat is gebouwd (doc-only, geen productiecode, geen tests):**

1. **`specs/openapi.yaml`** — zeven ontbrekende `'400'`-responses toegevoegd, elk een kaal blok met alleen een Nederlandse `description` die de concrete oorzaak benoemt:
   - `changePassword` en `resetUserPassword` — wachtwoord korter dan 4 tekens
   - `setUserRole` — beide bronnen: ongeldige rolwaarde én "Je kunt je eigen admin-rol niet verwijderen"
   - `deleteUser` — "Je kunt jezelf niet verwijderen"
   - `getDailyCosts` — `days` buiten 1..365
   - `getCostsByUser` — onbekende `period`
   - `getExternalCalls` — ongeldige ISO-8601 `from`/`to` (`limit` wordt geclamped, geeft géén 400)

2. **`specs/backend-technical-spec.md` §8** — het bullet "Code → contract" aangevuld met de 400-variant van de huisregel plus de verificatiegrep op `BadRequestException`, inclusief de uitleg waarom de telling niet één-op-één is.

**Gemaakte keuzes:**
- Gewerkt op `operationId`, niet op regelnummer — de regelnummers in het oorspronkelijke issue waren 5 te laag.
- Geen gedeelde `components/responses` geïntroduceerd; die conventie bestaat niet in dit bestand.
- Descriptions in het Nederlands (volgt het bestand), ook waar de onderliggende Kotlin-melding Engels is; de gebruikerszichtbare meldingen zijn letterlijk overgenomen.
- `getDailyCosts` krijgt een `'400'` ondanks `minimum`/`maximum` op de parameter, omdat Spring die grenzen niet valideert en de controller het zelf doet.

**Getest / geverifieerd:**
- `grep -c "'400'" specs/openapi.yaml` = **10** (was 3); `components/responses` blijft leeg.
- Diff op `openapi.yaml`: **16 toegevoegd, 0 verwijderd** (2 extra regels boven de verwachte 14 doordat de `setUserRole`-description een block scalar is — AC5 staat dat expliciet toe).
- YAML hard geparsed (SnakeYAML door dev + reviewer, js-yaml boomdiff door tester): exact 7 toevoegingen, alle van de vorm `/paths/…/responses/400`; 46 paths en 33 schemas onveranderd.
- Alle 12 `BadRequestException`-throw-sites herleid tot een operatie met een `'400'`.
- `mvn -B --no-transfer-progress clean verify` groen: exit 0, 129 unit + 77 e2e, 0 failures/errors. `AdminE2eTest` ongewijzigd.
- Live bewijs op preview: `PUT /api/account/password` met 3 tekens geeft 400, met geldig wachtwoord 200.

**Bewust niet gedaan:**
- Geen code-, test- of frontendwijziging.
- De vier al gedekte throw-sites (register, `PUT /api/rss-feeds`, `PUT /api/podcast-feeds`) niet aangeraakt.
- Geen andere statuscodes, schema's of prozateksten gewijzigd.
- De zes admin-400's zijn niet live bewezen: `/api/admin/*` geeft 403 vóór de validatie omdat er geen adminaccount op de preview is; vijf ervan zijn wel door `AdminE2eTest` gedekt.
