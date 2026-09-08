# SF-2228 - [Audit] Audit: corrigeer de `..`-claim bij de username-allowlist

## Story

[Audit] Audit: corrigeer de `..`-claim bij de username-allowlist

<!-- refined-by-factory -->

## Scope

Doc- en testcorrectie, **geen gedragswijziging**. De allowlist `USERNAME_PATTERN = Regex("^[A-Za-z0-9._-]{3,64}$")` (`AuthServiceImpl.kt:118`) sluit `..` niet uit — de punt zit in de tekenset. Gemeten: `a..b`, `...`, `..a` en `a..` worden geaccepteerd; `..` faalt alleen op de minimumlengte en `../x` faalt op de `/`. Vijf passages beweren het tegendeel.

De regex blijft ongewijzigd. De code is niet onveilig: omdat `/` en `\` wél zijn uitgesloten kan `..` nooit een eigen padsegment vormen, en `AdminServiceImpl.deleteAudioDir` heeft daaronder nog de containment-check op `<data-dir>/users/`.

**Te corrigeren passages (vijf, niet drie):**

1. `specs/backend-functional-spec.md:84` — "Elke andere vorm (leeg, te kort, te lang, met een `/`, met `..` of met een regeleinde) geeft een `400`".
2. `docs/factory/technical-spec.md:101` — "Eén positieve tekenset sluit in één regel `/`, `\`, `..`, spaties, null-bytes, regeleindes én de lege naam uit". (De story-tekst noemde `:100`; de werkelijke regel is `:101`.)
3. `AuthServiceImpl.kt:114` — KDoc bij de constante: "Sluit in één regel de lege naam, `/`, `\`, `..`, spaties, null-bytes en regeleindes uit".
4. `AuthServiceImpl.kt:35` — KDoc bij `register` zelf: "pad-veiligheid (… dus `/`, `\` en `..` mogen er nooit in zitten)". Zelfde onjuiste claim, in dezelfde file, in de story-tekst niet genoemd.
5. `specs/backend-technical-spec.md:320` — beschrijving van `AuthServiceImplRegisterTest`: "een naam met een `/`, met `..` of met een regeleinde levert een `BadRequestException`/400 op". Dezelfde onjuiste claim, en bovendien de plek waar het testaantal staat.

**Bewust buiten deze correctie (deze claims kloppen wél, niet aanpassen):**
- `specs/backend-functional-spec.md:60-61` en `AdminServiceImpl.kt:64` — die gaan over *bestaande* accounts van vóór de allowlist, die inderdaad nog een `..` of `/` in de naam kunnen hebben. Dat is de reden dat de containment-check bestaat.
- Treffers in `docs/stories/` — historische verslagen.

**Wijziging in de tests** (`newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/auth/domain/AuthServiceImplRegisterTest.kt`, nu 10 tests):
- `:77` heet `rejects a username containing a parent directory segment` maar test `"../x"`, dat op de `/` afketst — hernoemen naar wat hij bewijst (de `/`).
- Eén test erbij die vastlegt dat `a..b` juist **wél** wordt geaccepteerd, in de stijl van de bestaande accepteer-tests (`:39`, `:53`), met een comment dat dit vastgelegd bestaand gedrag is en niet los "gerepareerd" mag worden (karakteriseringstest-huisregel, `docs/factory/technical-spec.md`).
- Daardoor gaat het aantal van 10 naar 11 → het getal op `specs/backend-technical-spec.md:320` mee bijwerken.

**Buiten scope:** de allowlist zelf verscherpen (gedragswijziging, kan bestaande accounts raken) en het gedeelde `AuthRequest`-schema in `specs/openapi.yaml` dat de allowlist ook aan `login` oplegt — eigen story.

## Acceptance criteria

1. Geen van de vijf genoemde passages noemt `..` nog als iets dat de tekenset uitsluit; `/`, `\`, spaties, null-bytes, regeleindes en de lege/te korte/te lange naam blijven in elke opsomming staan.
2. Op `docs/factory/technical-spec.md:101` en in de KDoc bij `USERNAME_PATTERN` staat één zin met de werkelijke reden: `..` is niet uitgesloten, maar kan door het ontbreken van `/` en `\` nooit een eigen padsegment worden; de containment-check in `AdminServiceImpl.deleteAudioDir` is de laag daaronder.
3. `USERNAME_PATTERN` in `AuthServiceImpl.kt` is byte-voor-byte ongewijzigd; `git diff` toont geen wijziging aan de regex-regel.
4. `echo 'a..b' | grep -E '^[A-Za-z0-9._-]{3,64}$'` matcht — dit blijft het bewijs waartegen de tekst controleerbaar is.
5. `AuthServiceImplRegisterTest` bevat een test die `a..b` accepteert (een token teruggeeft, geen `BadRequestException`), met het karakteriserings-comment erbij.
6. De testnaam op `:77` verwijst niet meer naar een "parent directory segment" maar naar de `/` die de afwijzing veroorzaakt.
7. `specs/backend-technical-spec.md:320` noemt het juiste aantal tests (11) en beschrijft de dekking zoals die na deze wijziging is.
8. `grep -rn '`\.\.`' --include=*.md --include=*.kt . | grep -v docs/stories` levert alleen nog treffers op die over bestaande, niet-gevalideerde accounts gaan (`backend-functional-spec.md:61`, `AdminServiceImpl.kt:64`) — geen enkele treffer beweert nog dat de allowlist `..` uitsluit.
9. `mvn -B --no-transfer-progress clean test` blijft groen (baseline op deze branch: 129 tests, geen Docker nodig; wordt 130 na de extra test).

## Aannames

- **Beide** opties uit de oorspronkelijke scope worden uitgevoerd (hernoemen én een accepteer-test toevoegen), niet één van de twee: de misleidende testnaam is zelf onderdeel van de foutieve claim, en zonder de accepteer-test staat het werkelijke gedrag nergens in code vast — precies de val die de huisregel over karakteriseringstests beschrijft.
- De regeltest op `:83`/`:86` mag `"../x"` als invoer houden; die test bewijst de volgorde van de checks, niet de reden van de afwijzing.
- De nieuwe accepteer-test volgt het mockpatroon van de bestaande tests in dezelfde klasse (`UserRepository`/`JwtService`/`ApplicationEventPublisher` gemockt); er is geen nieuwe testinfrastructuur nodig.
- Geen wijziging aan `specs/openapi.yaml`, frontend, database of infrastructuur.
- Regelnummers zijn geverifieerd op branch `ai/SF-2228` (HEAD `bb8e4da`); ze kunnen één of twee regels schuiven zodra de eerste edit is toegepast — zoek op de geciteerde tekst, niet blind op het nummer.

## Eindsamenvatting

## Eindsamenvatting SF-2228 — Correctie van de `..`-claim bij de username-allowlist

### Wat is gebouwd
Een **doc- en testcorrectie zonder gedragswijziging**. De allowlist voor nieuwe gebruikersnamen (`USERNAME_PATTERN = ^[A-Za-z0-9._-]{3,64}$`) sluit `..` niet uit — de punt zit gewoon in de toegestane tekenset. Op vijf plekken in code-commentaar en specificaties stond het tegenovergestelde beweerd. Die vijf passages zijn gecorrigeerd:

| Plek | Wijziging |
|---|---|
| `specs/backend-functional-spec.md` §3 | `..` uit de opsomming van afgewezen vormen; `\`, spatie en null-byte staan er nu expliciet bij, plus één zin dat `a..b` wél wordt geaccepteerd |
| `docs/factory/technical-spec.md` | `..` uit de opsomming + de werkelijke reden waarom dat geen gat is |
| `AuthServiceImpl.kt` — KDoc bij `register` | "`/`, `\` en `..`" → "`/` en `\`" |
| `AuthServiceImpl.kt` — KDoc bij `USERNAME_PATTERN` | `..` uit de opsomming + verklarende zin met verwijzing naar de containment-check |
| `specs/backend-technical-spec.md` | testaantal 10 → 11 en de dekkingsbeschrijving herschreven |

Daarnaast in `AuthServiceImplRegisterTest`: de misleidende testnaam `rejects a username containing a parent directory segment` (invoer `../x`, die op de **slash** afketst) is hernoemd naar `rejects a relative path style username because of its slash, not its dots`, en er is één **karakteriseringstest** bijgekomen die vastlegt dat `a..b` juist wél een token oplevert.

### Gemaakte keuzes
- **De regex is byte-voor-byte ongewijzigd.** Verscherpen zou een gedragswijziging zijn die bestaande accounts kan raken — dat is bewust een aparte story.
- **Beide opties uitgevoerd** (hernoemen én accepteer-test), niet één van de twee: zonder de accepteer-test staat het werkelijke gedrag nergens in code vast.
- De nieuwe test heeft een expliciet comment dat hij bestaand gedrag vastlegt en niet los "gerepareerd" mag worden, maar alleen mag wijzigen in dezelfde diff die de regex verscherpt.
- De uitleg is toegevoegd in plaats van alleen weggehaald: `..` kan zonder `/` en `\` nooit een eigen padsegment worden, en de containment-check op `<data-dir>/users/` in `AdminServiceImpl.deleteAudioDir` is de laag daaronder.

### Wat is getest
- `mvn -B --no-transfer-progress clean verify` → BUILD SUCCESS, 142 unit + 77 e2e, 0 failures/errors (4:13 min). Losse testrun op de tester: **142 tests groen**, `AuthServiceImplRegisterTest` 11 tests (was 10).
- **Live gemeten op preview** `pnf-pr-237` (draaiende revisie geverifieerd via `/api/version`) met echte registraties: `a..b`, `...`, `..a`, `a..` → **201 + token**; `..`, `../x`, `a/b`, `a\b`, `a b`, `alice\nadmin` → **400**. De gecorrigeerde tekst is dus gemeten, niet aangenomen. De vier wegwerp-accounts zijn opgeruimd via `DELETE /api/account/me`.
- `echo 'a..b' | grep -E '^[A-Za-z0-9._-]{3,64}$'` matcht; `git diff main...HEAD` toont geen enkele wijziging aan de regex-regel.
- Alle 9 acceptatiecriteria door de tester geverifieerd.

### Bewust niet gedaan
- De allowlist zelf verscherpen (gedragswijziging, raakt mogelijk bestaande accounts) — aparte story.
- Het gedeelde `AuthRequest`-schema in `specs/openapi.yaml`, dat de allowlist ook aan `login` oplegt — aparte story.
- De `..`-vermeldingen die **wél** kloppen zijn ongemoeid gelaten: `specs/backend-functional-spec.md:61` en `AdminServiceImpl.kt:64` gaan over bestaande accounts van vóór de allowlist, en de treffers onder `docs/stories/` zijn historische verslagen.
- De volgordetest die ook `"../x"` gebruikt is ongewijzigd: die bewijst de volgorde van de checks, niet de reden van de afwijzing.
- Frontend, database en infrastructuur zijn niet geraakt.

### Aandachtspunt voor de PO
De acceptatiecriteria noemden een testbaseline van 129 → 130. Die was verouderd; de werkelijke baseline op deze branch is 141 → **142** (sinds SF-2207). Het delta van +1 klopt wel.
