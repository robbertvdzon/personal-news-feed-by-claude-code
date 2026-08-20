# SF-2228 — Corrigeer de `..`-claim bij de username-allowlist

Subtaak SF-2229 (development). Doc- en testcorrectie, **geen gedragswijziging**.

`USERNAME_PATTERN = Regex("^[A-Za-z0-9._-]{3,64}$")` sluit `..` niet uit — de punt zit in de
tekenset. Vijf passages beweerden het tegendeel. De regex zelf blijft byte-voor-byte ongewijzigd.

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en `technical-spec.md` gelezen
- [x] `..` uit de vijf opsommingen gehaald (2 docs in `specs/`, 1 in `docs/factory/`, 2 KDoc-blokken)
- [x] De werkelijke reden toegevoegd op `docs/factory/technical-spec.md` en in de KDoc bij `USERNAME_PATTERN`
- [x] Test `rejects a username containing a parent directory segment` hernoemd naar wat hij bewijst
- [x] Karakteriseringstest toegevoegd die vastlegt dat `a..b` wél wordt geaccepteerd
- [x] Testaantal in `specs/backend-technical-spec.md` bijgewerkt (10 → 11) en de dekking herschreven
- [x] Vangnet gedraaid: `mvn -B --no-transfer-progress clean verify`

## Wat is er gewijzigd en waarom

| Bestand | Wijziging |
|---|---|
| `specs/backend-functional-spec.md` (§3, Gebruikersnaam) | `..` uit de opsomming van afgewezen vormen; `\`, spatie en null-byte staan er nu expliciet bij, plus één zin dat `a..b` wél wordt geaccepteerd |
| `docs/factory/technical-spec.md` (allowlist-bullet) | `..` uit de opsomming, plus de werkelijke reden: zonder `/` en `\` kan `..` nooit een eigen padsegment worden, en `AdminServiceImpl.deleteAudioDir` is de laag daaronder |
| `AuthServiceImpl.kt` — KDoc bij `register` | "`/`, `\` en `..` mogen er nooit in zitten" → "`/` en `\` mogen er nooit in zitten" |
| `AuthServiceImpl.kt` — KDoc bij `USERNAME_PATTERN` | `..` uit de opsomming, plus dezelfde verklarende zin met verwijzing naar de containment-check |
| `specs/backend-technical-spec.md` (§ unit-tests) | 10 → 11 tests, `..` uit de afwijzingsopsomming, `../x` beschreven als afwijzing op de slash, en de nieuwe karakteriseringstest benoemd |
| `AuthServiceImplRegisterTest.kt` | Test hernoemd naar `rejects a relative path style username because of its slash, not its dots`; nieuwe test `accepts a username containing consecutive dots` met karakteriserings-comment |

Bewust **niet** aangepast (deze `..`-vermeldingen kloppen — ze gaan over bestaande accounts van
vóór de allowlist): `specs/backend-functional-spec.md:61`, `AdminServiceImpl.kt:64` en alle
treffers onder `docs/stories/`. De volgordetest die óók `"../x"` als invoer gebruikt is ongewijzigd
gebleven; die bewijst de volgorde van de checks, niet de reden van de afwijzing.

## Verificatie

- `echo 'a..b' | grep -E '^[A-Za-z0-9._-]{3,64}$'` → matcht (bewijs dat `..` is toegestaan).
- `git diff` op `AuthServiceImpl.kt` toont **geen** wijziging aan de `USERNAME_PATTERN`-regel.
- `mvn -B --no-transfer-progress clean verify` → **BUILD SUCCESS**, 142 unit + 77 e2e, 0 failures,
  0 errors, 4:13 min. `AuthServiceImplRegisterTest` draait 11 tests (was 10).
- `grep -icE 'warning|deprecat|self-attach'` op de verify-log = 1: de bekende Nederlandse
  `[Podcast]`-logregel met het woord "warnings" erin, geen regressie.
- `target/jacoco.exec` (373.756 B) en `target/jacoco-it.exec` (9,2 MB) zijn geschreven.

**Afwijking van de story-tekst:** de acceptatiecriteria noemen een baseline van 129 unit-tests
(→ 130). De werkelijke baseline op deze branch is 141 (sinds SF-2207); na deze story 142. Het
getal in de story was verouderd, het delta van +1 klopt wel.
