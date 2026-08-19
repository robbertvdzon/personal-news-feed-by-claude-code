# SF-2207 - Worklog

Story-context bij eerste pickup:
Username-validatie bij registratie + pad-veilige audio-opruiming

Voer het volledige ontwikkelwerk uit conform het implementatieplan in docs/stories/worklog/SF-2207-worklog.md. De refiner-comment (issue comment 3638) is leidend waar die van de oorspronkelijke storytekst afwijkt.

1) auth/domain/AuthServiceImpl.kt: voeg in register(), NA de wachtwoordcheck en VOOR de duplicaat-check, een allowlist-validatie op username toe (3-64 tekens, uitsluitend [A-Za-z0-9._-], als een Regex-constante). Weiger met BadRequestException (400) en een Engelstalige melding in de stijl van de bestaande wachtwoordmelding; noem de afgewezen naam niet in de melding. Leg in een KDoc-regel de reden vast: pad-veiligheid + logveiligheid. login, changePassword, resetPassword en setRole blijven ongewijzigd zodat bestaande accounts met een afwijkende naam kunnen blijven inloggen.

2) admin/domain/AdminServiceImpl.kt: maak deleteAudioDir pad-veilig. Bepaal basis en doelpad via toAbsolutePath().normalize() en verwijder alleen als het doelpad met startsWith aantoonbaar onder <dataDir>/users valt; anders niets verwijderen en een log.warn. Gebruik GEEN toRealPath() (app.data-dir default ./data hoeft niet te bestaan -> NoSuchFileException). De methode blijft private met dezelfde signatuur. Noem in de KDoc expliciet dat dit de vangnetlaag is voor accounts van voor de nieuwe validatie, en dat een lege naam (<dataDir>/users/audio) bewust door laag 1 wordt afgevangen en niet hier.

3) specs/openapi.yaml: geef AuthRequest.username dezelfde pattern/minLength/maxLength als de code afdwingt (naast het bestaande example). De '400' op POST /api/auth/register bestaat al - verbreed alleen die description zodat hij zowel het te korte wachtwoord als de ongeldige gebruikersnaam benoemt. Voeg geen nieuwe responsesleutel toe.

4) specs/backend-functional-spec.md §3 Authenticatie: neem de nieuwe regel op direct naast de bestaande wachtwoordparagraaf (rond regel 73), in dezelfde stijl (wat wordt afgedwongen, waar, met welke statuscode). Let op: backend-technical-spec.md §3 gaat over Spring Modulith en is NIET de juiste plek.

5) Tests (onderdeel van dit ontwikkelwerk), JUnit 5 + Mockito in de stijl van settings/domain/SettingsServiceImplSaveRssFeedsTest.kt (@ExtendWith(MockitoExtension::class) + mock(...)):
- Nieuwe auth/domain/AuthServiceImplRegisterTest.kt: geldige naam slaagt; per geweigerde vorm een test (leeg, te kort <3, te lang >64, met /, met .., met \n); plus een test dat een ongeldige naam 400 geeft en geen 409.
- Nieuwe admin/domain/AdminServiceImplDeleteAudioDirTest.kt: loopt via deleteUser(target, actor) met een gemockte AuthService waarvan deleteUser true geeft. Met @TempDir als dataDir en een bestand naast de datadirectory aantonen dat een traversal-naam daar niets verwijdert en dat het account zelf wel verwijderd wordt; plus de positieve kant: een geldige naam met bestaande map onder <dataDir>/users/<naam>/audio wordt nog steeds volledig opgeruimd.

Houd regex, minimum en maximum letterlijk gelijk in code, openapi en functional-spec. Draai afsluitend vanuit newsfeedbackend/newsfeedbackend: mvn -B --no-transfer-progress clean test | tee /tmp/mvntest.log en controleer dat de build groen is (129 bestaande + nieuwe tests) en dat grep -icE 'warning|deprecat|self-attach' /tmp/mvntest.log 0 geeft (grep nooit op WARN). Verwacht dat geen enkele bestaande test aangepast hoeft te worden; moet dat toch, meld dat expliciet. Werk het worklog bij en doe een eigen review-stap op je diff voordat je afrondt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **Laag 1 — `auth/domain/AuthServiceImpl.kt`**: `register()` valideert de gebruikersnaam tegen
  de constante `USERNAME_PATTERN` (`^[A-Za-z0-9._-]{3,64}$`, in het `companion object`). De check
  staat ná de wachtwoordcheck en vóór de duplicaat-check, zodat een ongeldige naam altijd `400`
  geeft en nooit `409`. Melding: `"Username must be 3-64 characters and may only contain letters,
  digits, '.', '_' and '-'"` — de afgewezen naam komt er bewust niet in terug. KDoc legt de reden
  vast (pad-veiligheid + logveiligheid) en waarom `login`/`changePassword` níét valideren.
- **Laag 2 — `admin/domain/AdminServiceImpl.kt`**: `deleteAudioDir` (blijft `private`, zelfde
  signatuur) bepaalt basis en doelpad met `toAbsolutePath().normalize()` en verwijdert alleen als
  het doelpad met `startsWith` onder `<dataDir>/users` valt; anders niets verwijderen + `log.warn`.
  Geen `toRealPath()`, want `app.data-dir` (default `./data`) hoeft niet te bestaan. Dit is de
  vangnetlaag voor accounts van vóór laag 1; de lege naam (`<dataDir>/users/audio`) valt technisch
  ónder de basis en wordt bewust door laag 1 afgevangen — beide punten staan in de KDoc.
- **Contract — `specs/openapi.yaml`**: `AuthRequest.username` heeft nu `pattern`, `minLength: 3` en
  `maxLength: 64`, letterlijk gelijk aan de code. De bestaande `'400'` van `register` is alleen
  verbreed ("… of ongeldige gebruikersnaam (3-64 tekens uit [A-Za-z0-9._-])"); geen nieuwe
  responsesleutel. YAML opnieuw geparsed met SnakeYAML 2.5 — parse ok, waarden exact.
- **Spec-tekst — `specs/backend-functional-spec.md` §3**: nieuwe alinea **Gebruikersnaam** direct
  naast de wachtwoordalinea, in dezelfde stijl (wat, waar, welke statuscode).
- **Tests (nieuw, 12 stuks)**:
  - `auth/domain/AuthServiceImplRegisterTest.kt` (10): geldige naam + geldige naam met cijfers/
    `.`/`_`/`-` slagen; afwijzing voor leeg, `"ab"`, 65 tekens, `"a/b"`, `"../x"` en `"alice\nadmin"`;
    één test dat een ongeldige naam de repository niet raakt (dus `400`, geen `409`) en één dat de
    wachtwoordcheck vóór de naamcheck komt.
  - `admin/domain/AdminServiceImplDeleteAudioDirTest.kt` (2): via `deleteUser(target, actor)` met
    gemockte `AuthService`; met `@TempDir` blijft een bestand náást de datadirectory bestaan bij een
    traversal-naam terwijl `auth.deleteUser` wél is aangeroepen, en een geldige naam ruimt
    `<dataDir>/users/alice/audio` nog volledig op.
  - Detail: Mockito's `argThat`/`any` geven `null`, wat Kotlin niet accepteert voor de non-null
    parameter van `UserRepository.add`. Opgelost met een kleine `userWith { … }`-wrapper die de
    matcher registreert en een dummy-`User` teruggeeft.
- **Vangnet**: `mvn -B --no-transfer-progress clean test` → exit 0, **141 tests** (129 bestaand + 12
  nieuw), 0 failures/errors; `grep -icE 'warning|deprecat|self-attach'` op de log = **0**;
  `target/jacoco.exec` geschreven (373 KB). Daarna het volledige vangnet uit
  `.factory/verification.yaml`: `mvn -B --no-transfer-progress clean verify` → exit 0, 141 unit +
  **77 e2e**, 4:12 min. Geen enkele bestaande test hoefde aangepast te worden; e2e-namen
  (`prefix-<8 hex>` en de shared-user `robbert`) voldoen al aan de allowlist.
