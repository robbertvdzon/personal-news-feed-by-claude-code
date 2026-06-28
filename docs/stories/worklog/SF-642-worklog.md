# SF-642 - Worklog

Story-context bij eerste pickup:
E2e-scenario's API-log en Admin toevoegen + tabellen/worklog bijwerken

Verifieer eerst de exacte UI-teksten/iconen/navigatie in frontend/lib/screens/api_log_screen.dart, admin_screen.dart, admin_costs_screen.dart en de Debug-/Beheer-secties in settings_screen.dart. Maak e2e/scenarios/api-log-scenario.md (Instellingen→Debug→API-log: lijst API-calls, detail-tap met URL/status/error-body, acties 'Kopieer alles'/snackbar 'Gekopieerd' en 'Log wissen', incl. faal-/leeg-condities) en e2e/scenarios/admin-scenario.md (Instellingen→admin-only sectie Beheer→AdminScreen gebruikersbeheer /api/admin/users en doorgenavigeerd AdminCostsScreen /api/admin/costs met totalen vandaag/maand/jaar/all + dag- en per-user-totalen, inclusief ⏭ Skipped/⚠️ Partial-pad zonder ROLE_ADMIN). Volg de bestaande conventie (NL, kopjes Doel/Voorwaarden/Stappen/Verwacht resultaat, start-scenario als voorwaarde). Werk e2e/readme.md (mappenstructuur + opsomming) en specs/e2e.md (tabelrij) bij naar de volledige set. Schrijf docs/stories/worklog/SF-642-worklog.md met toegevoegde scenario's, nu gedekte flows en wat buiten scope bleef (adhoc, backend-integratietests vanwege ontbrekende Postgres). Wijzig GEEN productiecode (backend Kotlin, Flutter) en geen bestaande unit-tests. Sluit af met een eigen review-stap op consistentie tussen scenario's, tabellen en feitelijke UI-teksten.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-643 — developer-run

### Toegevoegde scenario's
- **`e2e/scenarios/api-log-scenario.md`** — dekt Instellingen → sectie **Debug** → tegel
  **"API-log"** (`bug_report_outlined`) → `ApiLogScreen` (AppBar **"API-log"**). Geverifieerd
  en beschreven: de `API_BASE_URL`-header, de lijst met laatste calls (status-badge, methode+URL,
  tijd/duur), detail-tap (volledige URL, status, ISO-tijd/duur, **"Error / body:"** bij fouten,
  knoppen **"Kopieer"**/**"Sluiten"**), en de AppBar-acties **"Kopieer alles"** (`copy_all` →
  snackbar **"Gekopieerd"**) en **"Log wissen"** (`delete_outline` → lege staat met placeholder
  "Nog geen calls gelogd…"). Inclusief leeg-/faal-condities.
- **`e2e/scenarios/admin-scenario.md`** — dekt de admin-only sectie **Beheer** in Instellingen
  (tegels **"Beheer gebruikers"** `person_outline` → `AdminScreen`, AppBar **"Admin"**,
  `/api/admin/users`; en **"Beheer kosten"** `attach_money` → `AdminCostsScreen`, AppBar
  **"Kosten"**, `/api/admin/costs/{totals,daily,by-user,calls}`). Beschrijft gebruikersbeheer
  (rol-labels, "jij"-chip, per-user popupmenu Wachtwoord resetten / Maak admin / Maak gewone user /
  Verwijderen — niet-destructief, alleen bekeken/geannuleerd in de run), het kosten-overzicht
  (totaal-kaarten Vandaag/Deze maand/Dit jaar/Totaal + tabs **Per dag**/**Per gebruiker**/
  **Logboek** met hun lege-staat-placeholders), én een expliciet **⏭ Skipped**-pad voor wanneer
  het testaccount geen `ROLE_ADMIN` heeft (sectie Beheer afwezig = verwacht gedrag) plus een
  ⚠️ Partial-pad.

### Nu gedekte functionele flows
Met deze twee scenario's dekt de markdown-e2e-suite naast de bestaande flows (start, cleanup,
rss, feed, samenvatting, events, podcast, settings) nu ook: de **API-log/debug-weergave** en de
**admin-/beheer-schermen** (gebruikersbeheer + kosten-overzicht). Daarmee zijn alle functionele
Flutter-schermen die via de browser-e2e bereikbaar zijn afgedekt.

### Bijgewerkte overzichten
- `e2e/readme.md`: mappenstructuur-lijst én de "één of meer scenario's"-opsomming aangevuld met
  de volledige set (incl. de twee nieuwe en de reeds bestaande events/podcast/settings).
- `specs/e2e.md`: de scenario-tabelrij aangevuld tot de volledige set.

### Bewust buiten scope
- **adhoc**: er is geen Flutter-scherm voor (alleen backend + admin-kostencategorie), dus niet via
  browser-e2e te dekken.
- **Backend-integratietests** (Cucumber/WireMock/`*IT.kt`/`@SpringBootTest`): niet opgetuigd. Ze
  vereisen een Postgres die in de factory-omgeving ontbreekt; opzetten zou de "alle tests
  slagen"-randvoorwaarde breken. De bestaande backend test-suite (3 unit-tests) blijft ongewijzigd.

### Validatie
- De e2e-suite is geen testframework maar afspeelbare markdown — er is dus niets te "draaien".
  Validatie = consistentiecheck: alle UI-teksten/iconen/AppBar-titels/endpoints in de twee nieuwe
  scenario's zijn 1-op-1 geverifieerd tegen `frontend/lib/screens/api_log_screen.dart`,
  `admin_screen.dart`, `admin_costs_screen.dart` en de Debug-/Beheer-secties in
  `settings_screen.dart`.
- Geen productiecode of bestaande unit-tests gewijzigd, dus `mvn test` (vereist `PNF_DATABASE_URL`)
  is functioneel niet geraakt; de Flutter-runner heeft geen flutter/dart-binary, maar er is ook
  geen Flutter-code gewijzigd. CI valideert build/tests zoals gebruikelijk.
