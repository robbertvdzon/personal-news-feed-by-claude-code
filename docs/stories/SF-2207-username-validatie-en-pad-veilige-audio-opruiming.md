# SF-2207 - [Audit] Valideer de gebruikersnaam bij registratie en maak de audio-opruiming pad-veilig

## Story

Een gebruikersnaam werd bij registratie niet gevalideerd en kwam ongefilterd terecht in een pad dat
recursief verwijderd wordt (`AdminServiceImpl.deleteAudioDir`). Registratie staat open, dus een
aanvaller koos de naam zelf (bijv. `../../../../var/lib/iets`). Dezelfde ontbrekende validatie maakte
logvervalsing via een regeleinde mogelijk en liet een lege naam toe.

De oplossing is tweelaags: een strikte allowlist bij registratie (laag 1) en een containment-check op
het opruimpad (laag 2, vangnet voor accounts van vóór laag 1).

## Stappenplan

- [x] Story, factory-docs, `specs/backend-technical-spec.md` en `specs/openapi.yaml` gelezen
- [x] Laag 1: allowlist-validatie in `AuthServiceImpl.register`
- [x] Laag 2: containment-check in `AdminServiceImpl.deleteAudioDir`
- [x] Contract bijgewerkt in `specs/openapi.yaml`
- [x] Spec-tekst bijgewerkt in `specs/backend-functional-spec.md` §3
- [x] Unittests geschreven voor beide lagen
- [x] Volledig vangnet gedraaid (`mvn clean test` én `mvn clean verify`)
- [x] Story-log en worklog bijgewerkt

## Wat is gebouwd

**Laag 1 — `auth/domain/AuthServiceImpl.kt`.** `register()` valideert de gebruikersnaam tegen de
constante `USERNAME_PATTERN` (`^[A-Za-z0-9._-]{3,64}$`) in het `companion object`. De check staat ná
de wachtwoordcheck en vóór de duplicaat-check, zodat een ongeldige naam altijd `400` geeft en nooit
`409`. De melding is Engelstalig, in de stijl van de bestaande wachtwoordmelding, en noemt de
afgewezen naam bewust niet. `login`, `changePassword`, `resetPassword` en `setRole` blijven
ongewijzigd, zodat bestaande accounts met een afwijkende naam kunnen blijven inloggen.

**Laag 2 — `admin/domain/AdminServiceImpl.kt`.** `deleteAudioDir` (blijft `private`, zelfde
signatuur) bepaalt basis (`<dataDir>/users`) en doelpad met `toAbsolutePath().normalize()` en
verwijdert alleen als het doelpad met `startsWith` aantoonbaar onder de basis valt. Valt het
erbuiten: niets verwijderen, wel een `log.warn`. Het account zelf wordt nog steeds verwijderd.

**Contract en spec.** `AuthRequest.username` in `specs/openapi.yaml` draagt nu `pattern`,
`minLength: 3` en `maxLength: 64`, letterlijk gelijk aan de code; de bestaande `'400'` van
`register` is alleen verbreed en benoemt nu zowel het te korte wachtwoord als de ongeldige
gebruikersnaam (geen nieuwe responsesleutel). `specs/backend-functional-spec.md` §3 kreeg een alinea
**Gebruikersnaam** direct naast de wachtwoordalinea.

**Tests (12 nieuw).** `auth/domain/AuthServiceImplRegisterTest.kt` (10) dekt de geldige naam en elke
geweigerde vorm (leeg, te kort, te lang, `/`, `..`, regeleinde), plus de volgorde van de checks.
`admin/domain/AdminServiceImplDeleteAudioDirTest.kt` (2) loopt via `deleteUser(target, actor)` met
een gemockte `AuthService` en toont met `@TempDir` aan dat een traversal-naam niets buiten
`<dataDir>/users/` verwijdert, terwijl een geldige naam zijn audiomap nog steeds volledig opruimt.

## Keuzes

- Eén `Regex`-constante in plaats van losse lengte-/tekenchecks: sluit `/`, `\`, `..`, regeleindes,
  spaties, null-bytes en de lege naam in één regel uit. Bewuste consequentie: namen van 1-2 tekens
  zijn voortaan ongeldig bij registratie.
- Géén `toRealPath()` in laag 2: `app.data-dir` heeft default `./data` en hoeft niet te bestaan —
  `toRealPath()` zou dan een `NoSuchFileException` gooien.
- De lege naam (`<dataDir>/users/audio`) valt technisch ónder de basis en wordt door laag 2 dus niet
  geweigerd; laag 1 vangt die vorm af. Beide punten staan expliciet in de KDoc.
- Mockito's `argThat`/`any` geven `null`, wat Kotlin niet accepteert voor de non-null parameter van
  `UserRepository.add`. Opgelost met een kleine `userWith { … }`-wrapper in de test die de matcher
  registreert en een dummy-`User` teruggeeft.

## Wat is getest

- `mvn -B --no-transfer-progress clean test`: exit 0, **141 tests** (129 bestaand + 12 nieuw), 0
  failures/errors. `grep -icE 'warning|deprecat|self-attach'` op de log = **0**; `target/jacoco.exec`
  geschreven (373 KB).
- `mvn -B --no-transfer-progress clean verify` (het vangnet uit `.factory/verification.yaml`): exit
  0, 141 unit + **77 e2e**, 4:12 min.
- `specs/openapi.yaml` opnieuw geparsed met SnakeYAML 2.5 — parse ok, `AuthRequest.username` draagt
  exact `pattern`/`minLength`/`maxLength`.
- Geen enkele bestaande test hoefde aangepast te worden: e2e-namen (`prefix-<8 hex>` via
  `E2eTestBase.uniqueUsername` en de shared-user `robbert`) voldoen al aan de allowlist.

## Bewust niet gedaan

- Bestaande accounts migreren of hernoemen — laag 2 is expliciet het vangnet daarvoor.
- Bredere bean-validation (`@Valid` op alle DTO's) en rate limiting: aparte, grotere bevinding.
- `deleteAudioDir` helemaal verwijderen (de MP3's staan inmiddels in Postgres) — dat is een
  gedragswijziging die om een eigen afweging vraagt.
- De in-memory sleutel `"$username/$id"` in `RequestServiceImpl.kt` is niet aangepast; de allowlist
  maakt hem vanzelf eenduidig.
- Geen frontend-wijziging: de nieuwe `400` gedraagt zich in de Flutter-app identiek aan de bestaande
  wachtwoord-`400`. Een specifiekere Nederlandse melding is een aparte verbetering.
