# SF-2228 - Worklog

Story-context bij eerste pickup:
Corrigeer de `..`-claim op vijf plekken en leg het werkelijke gedrag in tests vast

Doc- en testcorrectie, GEEN gedragswijziging. `USERNAME_PATTERN = Regex("^[A-Za-z0-9._-]{3,64}$")` in AuthServiceImpl.kt blijft byte-voor-byte ongewijzigd (controleer met git diff).

1) Haal `..` uit de opsomming op VIJF plekken (zoek op de geciteerde tekst, niet op regelnummer - nummers schuiven na de eerste edit): (a) specs/backend-functional-spec.md:84 'leeg, te kort, te lang, met een `/`, met `..` of met een regeleinde'; (b) docs/factory/technical-spec.md:101 'sluit in een regel `/`, `\`, `..`, spaties, null-bytes, regeleindes en de lege naam uit'; (c) AuthServiceImpl.kt:114 KDoc bij de constante; (d) AuthServiceImpl.kt:35 KDoc bij `register` ('dus `/`, `\` en `..` mogen er nooit in zitten'); (e) specs/backend-technical-spec.md:320 testbeschrijving. Alle andere items (`/`, `\`, spaties, null-bytes, regeleindes, leeg/te kort/te lang) blijven staan.

2) Voeg op technical-spec.md:101 en in de KDoc bij USERNAME_PATTERN EEN zin toe met de werkelijke reden: `..` is niet uitgesloten, maar kan door het ontbreken van `/` en `\` nooit een eigen padsegment worden; de containment-check in AdminServiceImpl.deleteAudioDir op `<data-dir>/users/` is de laag daaronder.

3) Tests in newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/auth/domain/AuthServiceImplRegisterTest.kt (nu 10 tests): hernoem `rejects a username containing a parent directory segment` (invoer "../x") naar wat hij bewijst - de afwijzing komt van de `/`. LET OP: de test op de regel erboven heet al `rejects a username containing a slash` ("a/b"), kies dus een onderscheidende naam, geen duplicaat. Voeg EEN accepteer-test toe die vastlegt dat `a..b` wel een token oplevert (geen BadRequestException), in de stijl van de bestaande accepteer-tests met gemockte UserRepository/JwtService/ApplicationEventPublisher, met een comment dat dit een karakteriseringstest van bestaand gedrag is en niet los 'gerepareerd' mag worden. De volgordetest die ook "../x" gebruikt blijft ongewijzigd.

4) Werk het testaantal op specs/backend-technical-spec.md:320 bij van 10 naar 11 en beschrijf de dekking zoals die na deze wijziging is.

NIET AANPASSEN (deze `..`-vermeldingen zijn correct - ze gaan over bestaande accounts van voor de allowlist): specs/backend-functional-spec.md:60-61, AdminServiceImpl.kt:64, en alle treffers onder docs/stories/.

Verificatie voor je afrondt: `echo 'a..b' | grep -E '^[A-Za-z0-9._-]{3,64}$'` matcht nog; `mvn -B --no-transfer-progress clean test` groen (129 -> 130 tests); git diff toont geen wijziging aan de regex-regel. Sluit af met een eigen reviewstap over de volledige diff.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-2229 (developer) uitgevoerd: vijf `..`-claims gecorrigeerd, verklarende zin toegevoegd op
  `docs/factory/technical-spec.md` en in de KDoc bij `USERNAME_PATTERN`, misleidende testnaam
  hernoemd en een karakteriseringstest toegevoegd die `a..b` als geaccepteerd vastlegt.
  `USERNAME_PATTERN` is ongewijzigd (geen gedragswijziging).
- Vangnet groen: `mvn -B --no-transfer-progress clean verify` exit 0, 142 unit + 77 e2e,
  0 failures / 0 errors, 4:13 min; `AuthServiceImplRegisterTest` 11 tests (was 10).
- Let op voor volgende rollen: de story noemde een unit-baseline van 129 → 130; die was
  verouderd. Werkelijk 141 → 142 (sinds SF-2207).
- Volledige details in `docs/stories/SF-2228-corrigeer-dotdot-claim-username-allowlist.md`.
