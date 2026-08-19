# SF-2207 - [Audit] Valideer de gebruikersnaam bij registratie en maak de audio-opruiming pad-veilig

## Story

[Audit] Valideer de gebruikersnaam bij registratie en maak de audio-opruiming pad-veilig

<!-- refined-by-factory -->

## Scope

Een gebruikersnaam wordt bij registratie niet gevalideerd en komt ongefilterd terecht in een pad dat recursief verwijderd wordt.

- `auth/domain/AuthServiceImpl.kt:30-41` (`register`) controleert alleen `password.length < 4` (`:31`) en het bestaan van de naam (`:32`). Op `username` gebeurt niets: geen tekenset, geen lengte, geen leeg-check. `auth/api/AuthController.kt:18-19` geeft `body.username` ongefilterd door; er is nergens een `@Valid`.
- `admin/domain/AdminServiceImpl.kt:59-68` (`deleteAudioDir`, `private`) bouwt `Path.of(dataDir, "users", username, "audio")` (`:60`) en doet `Files.walk` (`:62`) met een recursieve `deleteIfExists` in omgekeerde volgorde (`:63-64`). Een grep op `normalize`, `startsWith` of `toRealPath` over `src/main` geeft nul containment-treffers.
- Registratie staat open, dus een aanvaller kiest de naam zelf (bijv. `../../../../var/lib/iets`). De delete vuurt via `DELETE /api/admin/users/{username}` en faalt per bestand stil (`runCatching{}.onFailure{ log.warn }`).
- `podcast/infrastructure/PodcastRepository.kt:13-19` legt vast dat de MP3-bytes in Postgres staan (`audio_bytes BYTEA`) en dat `${app.data-dir}/users/<u>/audio/` verlaten is. Voor een normale naam bestaat die map dus niet en valt `Files.exists` (`:61`) meteen terug — de enige situatie waarin deze delete écht iets verwijdert, is precies de situatie waarin de naam uit de datadirectory wegwijst.
- Dezelfde ontbrekende validatie maakt logvervalsing via een regeleinde mogelijk (`AuthServiceImpl.kt:39,:46`; `AdminServiceImpl.kt:34,45,56`), maakt een lege naam geldig (`Path.of` slaat het lege segment over → `<dataDir>/users/audio`) en maakt de in-memory sleutel `"$username/$id"` in `RequestServiceImpl.kt:37` in principe ambigu.

Op te leveren:

1. **Validatie in `AuthServiceImpl.register`**, ná de wachtwoordcheck en vóór de duplicaat-check. Weiger met een `BadRequestException` (400, in lijn met de bestaande wachtwoordcheck) elke naam die niet aan een strikte allowlist voldoet: 3-64 tekens, uitsluitend `[A-Za-z0-9._-]`. Leg de keuze vast in een KDoc-regel met de reden (pad-veiligheid + logveiligheid), zodat een volgende wijziging weet waarom de regel er staat. `login` en `changePassword` blijven ongewijzigd, zodat bestaande accounts met een afwijkende naam kunnen blijven inloggen.
2. **Containment-check in `AdminServiceImpl.deleteAudioDir`** als tweede laag, want bestaande accounts zijn niet met terugwerkende kracht gevalideerd. Bepaal de basis als `Path.of(dataDir, "users").toAbsolutePath().normalize()` en verwijder alleen als het eveneens genormaliseerde doelpad daar aantoonbaar ónder valt (`startsWith`). Gebruik géén `toRealPath()`: `app.data-dir` heeft default `./data` (`AdminServiceImpl.kt:23`) en hoeft niet te bestaan, dan gooit `toRealPath()` een `NoSuchFileException`. Valt het pad erbuiten: niets verwijderen, wel een `log.warn`. Noem in de KDoc expliciet dat dit de vangnetlaag is voor accounts van vóór punt 1.
3. **Contract bijwerken** in `specs/openapi.yaml`:
   - `AuthRequest.username` (`:1328-1330`) krijgt dezelfde `pattern`, `minLength` en `maxLength` als de code afdwingt, naast het bestaande `example: robbert`.
   - De `'400'` op `POST /api/auth/register` bestaat al (`:116-117`, description "Wachtwoord te kort (minimaal 4 tekens)"); die description wordt verbreed zodat hij óók de geweigerde gebruikersnaam benoemt — conform de huisregel op `specs/backend-technical-spec.md:576` dat een `'400'`-description de concrete oorzaak draagt. Er komt dus geen nieuwe responsesleutel bij.
4. **Tests** (JUnit 5 + Mockito, in de stijl van `settings/domain/SettingsServiceImplSaveRssFeedsTest.kt`: `@ExtendWith(MockitoExtension::class)` + `mock(...)` op de repositories):
   - Nieuwe `auth/domain/AuthServiceImplRegisterTest.kt`: een geldige naam slaagt; en per geweigerde vorm een test — leeg, te kort (< 3), te lang (> 64), met `/`, met `..`, met een regeleinde (`\n`).
   - Nieuwe `admin/domain/AdminServiceImplDeleteAudioDirTest.kt`: `deleteAudioDir` is `private`, dus de test loopt via `deleteUser(target, actor)` met een gemockte `AuthService` waarvan `deleteUser` `true` teruggeeft. Met `@TempDir` als `dataDir` en een bestand náást de datadirectory tonen dat een traversal-naam niets buiten `<dataDir>/users/` verwijdert.
5. **Spec-tekst**: neem de nieuwe regel op in `specs/backend-functional-spec.md` §3 Authenticatie, direct naast de bestaande wachtwoordparagraaf op regel 73 en in dezelfde stijl (wat wordt afgedwongen, waar, en met welke statuscode). Let op: `specs/backend-technical-spec.md` §3 gaat over Spring Modulith en bevat géén wachtwoordparagraaf — daar hoort deze tekst niet.

## Acceptance criteria

1. `POST /api/auth/register` met `username` = `""`, `"ab"`, een naam van meer dan 64 tekens, `"../x"`, `"a/b"` of een naam met een regeleinde geeft `400`; een gewone naam (bijv. `robbert` of `user-a1b2c3d4`) geeft nog steeds `201` met een bruikbaar token.
2. De validatie zit vóór de duplicaat-check, zodat een ongeldige naam `400` geeft en niet `409`, ook als die naam al zou bestaan.
3. `login` en `changePassword` valideren de gebruikersnaam niet: een bestaand account met een afwijkende naam kan blijven inloggen.
4. Een unittest toont aan dat `deleteAudioDir` via `deleteUser` met een traversal-naam niets buiten `<dataDir>/users/` verwijdert: een bestand naast de datadirectory bestaat na de aanroep nog. Bij afwijzing verschijnt een `log.warn`; het account zelf wordt nog steeds verwijderd (`auth.deleteUser` blijft aangeroepen).
5. Voor een geldige naam met een bestaande audiomap onder `<dataDir>/users/<naam>/audio` wordt die map nog steeds volledig opgeruimd.
6. `specs/openapi.yaml` `AuthRequest.username` draagt exact dezelfde grenzen als de code (`pattern`, `minLength`, `maxLength`), en de `'400'`-description van `register` benoemt zowel het te korte wachtwoord als de ongeldige gebruikersnaam.
7. `specs/backend-functional-spec.md` §3 beschrijft de nieuwe regel naast de wachtwoordparagraaf, met vermelding van het endpoint en de `400`.
8. `mvn -B --no-transfer-progress clean test` is groen: 129 bestaande tests plus de nieuwe tests uit punt 4, geen enkele bestaande test hoeft aangepast te worden. De buildlog blijft vrij van `warning`/`deprecat`-regels (gemeten met `grep -icE 'warning|deprecat'`; de bestaande legitieme logback-`WARN `-regels uit de SSRF-tests matchen daar niet op).

## Aannames

- **Regex**: `^[A-Za-z0-9._-]{3,64}$`, als één `Regex`-constante in `AuthServiceImpl`. Dat sluit `/`, `\`, `..`, regeleindes, spaties, null-bytes en de lege naam in één regel uit. Bewuste consequentie: namen van 1-2 tekens zijn voortaan ongeldig bij registratie.
- **Foutmelding**: Engelstalig en in de stijl van de bestaande wachtwoordmelding (`"Password must be at least 4 characters"`), dus bijv. `"Username must be 3-64 characters and may only contain letters, digits, '.', '_' and '-'"`. De naam zelf komt niet in de melding terug.
- **`deleteAudioDir` blijft `private`** en houdt zijn huidige signatuur; er komt geen nieuwe publieke API bij. De test bereikt hem via `deleteUser`.
- **Lege naam**: `<dataDir>/users/audio` valt technisch ónder `<dataDir>/users/` en wordt door de containment-check dus niet geweigerd. Punt 1 is de laag die deze vorm afvangt; dat is voldoende en de containment-check hoeft daar geen extra regel voor te krijgen.
- **Geen frontend-wijziging**: `frontend/lib/providers/auth_provider.dart:49-51` vangt elke `ApiException` af als `"Inloggen mislukt (${e.statusCode})"` en `login_screen.dart:66-68` toont dat. De nieuwe `400` gedraagt zich daar identiek aan de bestaande wachtwoord-`400`. Een specifiekere Nederlandse melding is een aparte verbetering.
- **Geen e2e-wijziging**: `E2eTestBase.uniqueUsername` bouwt `"$prefix-${UUID…take(8)}"` (max 17 tekens, alleen `[a-z0-9-]`) en voldoet dus aan de allowlist; `AuthE2eTest` en `AdminE2eTest` blijven ongewijzigd groen. Een e2e-test voor de nieuwe `400` is optioneel — de unittests uit punt 4 zijn de afgesproken dekking.
- **`app.shared-feed.username`** is een configuratiewaarde en loopt niet via `register`; die blijft buiten de validatie.

## Buiten scope

- Bestaande accounts migreren of hernoemen. De containment-check uit punt 2 is expliciet de vangnetlaag daarvoor.
- Bredere bean-validation (`@Valid` op alle DTO's) en rate limiting — aparte, grotere bevinding uit hetzelfde auditrapport.
- `deleteAudioDir` helemaal verwijderen. Verdedigbaar nu de MP3's in Postgres staan, maar dat is een gedragswijziging die om een eigen afweging vraagt — de methode blijft en wordt veilig gemaakt.
- De in-memory sleutel `"$username/$id"` in `RequestServiceImpl.kt:37` wordt niet aangepast; de allowlist maakt hem vanzelf eenduidig.
- Een gebruikerszichtbare Nederlandse foutmelding in de Flutter-app.
<!-- proposed-summary is de korte toelichting hierboven; dit blok is de story-description. -->

## Eindsamenvatting

Alles gelezen: `.task.md`, het worklog (developer + reviewer + tester) en de volledige diff `main...HEAD`.

## Eindsamenvatting SF-2207 — Username-validatie bij registratie + pad-veilige audio-opruiming

**Probleem**
Een gebruikersnaam werd bij registratie niet gevalideerd en kwam ongefilterd terecht in een pad dat recursief verwijderd wordt. Omdat registratie openstaat, kon een aanvaller zelf een naam als `../../../../var/lib/iets` kiezen; de admin-delete (`DELETE /api/admin/users/{username}`) liep dan met `Files.walk` buiten de datadirectory. Dezelfde ontbrekende validatie maakte logvervalsing via een regeleinde mogelijk en liet een lege naam toe.

**Wat is gebouwd (2 lagen + contract)**

1. **Laag 1 — validatie bij registratie** (`auth/domain/AuthServiceImpl.kt`): `register()` toetst de naam aan één constante `USERNAME_PATTERN` = `^[A-Za-z0-9._-]{3,64}$`. Afwijzing = `BadRequestException` (400) met een Engelstalige melding in de stijl van de bestaande wachtwoordmelding; de afgewezen naam komt bewust niet in de melding terug. De check staat ná de wachtwoordcheck en vóór de duplicaat-check, zodat een ongeldige naam altijd 400 geeft en nooit 409. `login`, `changePassword`, `resetPassword` en `setRole` zijn ongewijzigd, zodat bestaande accounts met een afwijkende naam kunnen blijven inloggen. KDoc legt de reden vast (pad-veiligheid + logveiligheid).
2. **Laag 2 — containment-check** (`admin/domain/AdminServiceImpl.kt`): `deleteAudioDir` (blijft `private`, zelfde signatuur) normaliseert basis en doelpad met `toAbsolutePath().normalize()` en verwijdert alleen als het doelpad met `startsWith` onder `<dataDir>/users` valt; anders niets verwijderen + `log.warn`. Bewust géén `toRealPath()` (default `./data` hoeft niet te bestaan → `NoSuchFileException`). Dit is de vangnetlaag voor accounts van vóór laag 1; het account zelf wordt ook bij een geweigerd pad nog verwijderd.
3. **Contract & spec**: `specs/openapi.yaml` `AuthRequest.username` draagt nu `pattern`/`minLength: 3`/`maxLength: 64`, letterlijk gelijk aan de code; de bestaande `'400'` van `register` is alleen verbreed (geen nieuwe responsesleutel). `specs/backend-functional-spec.md` §3 heeft een nieuwe alinea **Gebruikersnaam** direct naast de wachtwoordalinea.

**Wat is getest**
- `mvn clean test`: groen, **141 tests** (129 bestaand + 12 nieuw), 0 failures/errors; buildlog vrij van `warning`/`deprecat`. Volledig vangnet `mvn clean verify` ook groen: 141 unit + 77 e2e. **Geen enkele bestaande test hoefde aangepast te worden.**
- Nieuw: `AuthServiceImplRegisterTest` (10 tests — geldige namen, plus afwijzing voor leeg, `ab`, 65 tekens, `a/b`, `../x`, `alice\nadmin`, en de volgorde 400-vóór-409 en wachtwoord-vóór-naam) en `AdminServiceImplDeleteAudioDirTest` (2 tests met `@TempDir`: traversal-naam verwijdert niets náást de datadirectory terwijl `auth.deleteUser` wél loopt; geldige naam ruimt zijn audiomap nog volledig op).
- Reviewer heeft de regex los nagemeten (o.a. trailing `\n`/`\r`/spatie worden ook geweigerd) en het groene vangnet aan exact deze tree-sha gekoppeld.
- Tester heeft live op preview `pnf-pr-235` alle 400/201/409-gevallen bevestigd (incl. de grens van 64 tekens met werkend token) plus browserbewijs in de Flutter-UI; login met `a/b` geeft 401 en géén 400 — allowlist geldt alleen bij registratie.

**Bewust niet gedaan**
Geen frontend-wijziging (de UI toont de generieke "Inloggen mislukt (400)"), geen e2e-testuitbreiding, geen migratie/hernoeming van bestaande accounts (laag 2 is daarvoor het vangnet), geen bredere bean-validation of rate limiting, `deleteAudioDir` niet verwijderd, en de in-memory sleutel `"$username/$id"` niet aangepast.

**Openstaande, niet-blokkerende punten uit de review**
De traversal-test asserteert de `log.warn` niet (geen logcaptor); een naam met een null-byte zou theoretisch een 500 geven, maar is praktisch onbereikbaar (Postgres `text` accepteert die niet en laag 1 sluit hem af).

```json
```
