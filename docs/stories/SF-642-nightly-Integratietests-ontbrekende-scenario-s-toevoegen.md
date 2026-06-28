# SF-642 - nightly: Integratietests: ontbrekende scenario's toevoegen

## Story

nightly: Integratietests: ontbrekende scenario's toevoegen

<!-- refined-by-factory -->

## Scope

Breid de **e2e-suite** (`e2e/scenarios/`, vrije-tekst markdown-scripts conform `e2e/readme.md`) uit met scenario's voor de functionele schermen die nu nog niet gedekt zijn. Productiecode wordt **niet** gewijzigd; backend-integratietests (Cucumber/WireMock/`@SpringBootTest`) worden **niet** opgetuigd (vereisen een Postgres die in de factory ontbreekt en zouden de "alle tests slagen"-randvoorwaarde breken).

Toe te voegen scenario's:
1. **`api-log-scenario.md`** — Instellingen → sectie **Debug** → tegel **API-log** opent `ApiLogScreen` (AppBar "API-log"). Verifieer: lijst met laatste API-calls, tap-op-entry toont volledige URL/status/error-body, AppBar-acties **"Kopieer alles"** (`copy_all`, snackbar "Gekopieerd") en **"Log wissen"** (`delete_outline`).
2. **`admin-scenario.md`** — Instellingen → admin-only sectie **Beheer** → `AdminScreen` (gebruikersbeheer via `/api/admin/users`) en het door-genavigeerde **kosten**-scherm `AdminCostsScreen` (`/api/admin/costs`: totalen vandaag/maand/jaar/all, dag- en per-user-totalen). Graceful **⏭ Skipped** wanneer het e2e-testaccount geen admin-rol heeft (de sectie Beheer is dan afwezig).

Werk de overzichts­tabellen bij zodat ze de volledige set scenario's noemen:
- `e2e/readme.md` (mappenstructuur-lijst en de "één of meer scenario's"-opsomming),
- `specs/e2e.md` (tabelrij met de scenario-bestanden).

Buiten scope: **adhoc** (geen frontend-scherm → niet via browser-e2e te dekken), backend-integratietest-infrastructuur, en elke wijziging aan productiecode.

## Acceptance criteria

- [ ] `e2e/scenarios/api-log-scenario.md` bestaat en volgt de scenario-conventie uit `e2e/readme.md` (kopjes **Doel / Voorwaarden / Stappen / Verwacht resultaat**, NL, menselijk leesbaar), met `start-scenario` als voorwaarde. Het dekt: navigatie via Instellingen → Debug → API-log, de lijst met API-calls, detail-tap, en de acties "Kopieer alles" en "Log wissen", inclusief faal-condities.
- [ ] `e2e/scenarios/admin-scenario.md` bestaat, volgt dezelfde conventie, dekt gebruikersbeheer (`AdminScreen`) en het kosten-scherm (`AdminCostsScreen`), en bevat een **⏭ Skipped / ⚠️ Partial**-pad voor het geval het testaccount geen admin-rechten heeft.
- [ ] Nieuwe scenario's gebruiken alleen UI-teksten/iconen die feitelijk in de betreffende Flutter-schermen voorkomen (geverifieerd tegen `frontend/lib/screens/api_log_screen.dart`, `admin_screen.dart`, `admin_costs_screen.dart` en de Instellingen-secties in `settings_screen.dart`).
- [ ] `e2e/readme.md` en `specs/e2e.md` noemen de volledige, actuele set scenario-bestanden (incl. de twee nieuwe en de reeds bestaande events/podcast/settings).
- [ ] Geen wijziging aan productiecode (backend Kotlin, Flutter-frontends); de bestaande unit-tests blijven onveranderd en groen (`mvn test`, vereist `PNF_DATABASE_URL`).
- [ ] Geen backend-integratietests (Cucumber/WireMock/`*IT.kt`/`@SpringBootTest`) toegevoegd; de reden (geen Postgres in de factory) is kort vastgelegd in het worklog.
- [ ] Het worklog `docs/stories/worklog/SF-642-worklog.md` beschrijft welke scenario's zijn toegevoegd, welke functionele flows nu gedekt zijn, en wat bewust buiten scope bleef (adhoc, backend-integratietests).

## Aannames

- "Integratietests" = in deze repo concreet de **markdown-e2e-suite** in `e2e/scenarios/` (agent-gespeeld, geen test-framework); er bestaat geen werkende backend-integratietestlaag en die wordt niet opgezet (Postgres-afhankelijkheid ontbreekt in de factory).
- De e2e-scenario's voor **events, podcast en settings** bestaan al en zijn correct; ze worden niet overgedaan, hooguit licht bijgewerkt als een verwijzing naar de nieuwe scenario's nuttig is.
- **Adhoc** valt buiten browser-e2e omdat er geen Flutter-scherm voor is (alleen backend + admin-kostencategorie).
- Het admin-scenario kan in een feitelijke testrun **Skipped** zijn als het wegwerp-/vaste testaccount geen `ROLE_ADMIN` heeft; het scenario zelf is correct mits het dat pad documenteert.
- `docs/factory/` is volledig ingevuld (SF-220), dus er is geen docs-aanvulling-acceptatiecriterium nodig.
- "Geen functioneel gedrag wijzigen" betekent: uitsluitend bestanden onder `e2e/` en de docs-tabellen (`specs/e2e.md`) plus het worklog aanpassen.

<!-- test-feedback:start -->
## Test-feedback
## Testrapport — SF-644 (story-brede test van SF-642)

**Modus:** code-inspectie (geen browser-test). De story wijzigt **geen productiecode** — `git diff --name-status main...HEAD` toont uitsluitend `e2e/`-scenario's, `e2e/readme.md`, `specs/e2e.md`, story-doc en worklog. De beschreven Flutter-schermen bestaan ongewijzigd op `main`; een preview-screenshot zou identiek aan main zijn. De juiste verificatie voor deze docs-only PR is de scenario-tekst 1-op-1 tegen de bron-`.dart` leggen.

**Correct geverifieerd (1-op-1 tegen de bron):**
- `api-log-scenario.md` ↔ `api_log_screen.dart` + `settings_screen.dart` — alle AppBar-titels, iconen (`bug_report_outlined`, `copy_all`, `delete_outline`), teksten ("Gekopieerd", "Log wissen", `API_BASE_URL`, placeholder), detaildialoog en faal-condities kloppen.
- `admin-scenario.md` ↔ `admin_screen.dart` + `admin_costs_screen.dart` + `settings_screen.dart` — `auth.isAdmin`-gate, tegels, AppBars "Admin"/"Kosten", kaarten, tabs, kolommen, periode-/filter-chips, provider-initialen O/E/T/R/W, lege-staten, endpoints, `⏭ Skipped`/`⚠️ Partial`-paden — kloppen.
- `e2e/readme.md` en `specs/e2e.md` bevatten de volledige, actuele scenario-set.
- Geen backend-integratietests of unit-test-wijzigingen; `mvn test` functioneel niet geraakt (gedrag == main).

**Blocker — terug naar developer:**
In `admin-scenario.md` (leeswijzer, regels 43-46) staat dat **"Maak admin"** een bevestigingsdialoog toont. In `admin_screen.dart` `_handleAction` (regels 159-162) past `case 'make_admin'` de rol echter **direct** toe — géén `_confirm`. Alleen `case 'make_user'` en `case 'delete'` tonen de "Bevestig"-dialoog. De leeswijzer beschrijft dus gedrag dat het scherm niet heeft, wat in strijd is met het acceptatiecriterium "scenario's gebruiken alleen UI-teksten/gedrag dat feitelijk in de schermen voorkomt".

> De reviewer signaleerde dit al als `[suggestie]` (worklog regels 80-84) maar liet het bewust onverwerkt ("bij een volgende pass"). Omdat de hele waarde van deze deliverable de accuratesse van de e2e-documentatie is, is dit voor de tester een blocker, geen nit. **Fix:** haal "Maak admin" uit de bevestigingsdialoog-opsomming (alleen "Maak gewone user" en "Verwijderen" vragen bevestiging; "Maak admin" wordt direct toegepast).

Ik heb alleen het worklog aangepast; geen code/tests/infra aangeraakt en niets gecommit.

{"agent_tips_update":[{"category":"testing","key":"pnf-admin-screen-make-admin-no-confirm","content":"In frontend/lib/screens/admin_screen.dart _handleAction: 'make_admin' past de rol DIRECT toe (notifier.setRole), ZONDER _confirm-dialoog. Alleen 'make_user' (demote) en 'delete' tonen de 'Bevestig'-dialoog (Annuleren/Doorgaan); 'reset' toont 'Nieuw wachtwoord voor <user>' (Annuleren/Resetten). e2e/scenarios/admin-scenario.md beweerde foutief dat 'Maak admin' ook een bevestiging vraagt -> doc-accuratesse-bug. Controleer dit soort gedrag-claims altijd tegen de daadwerkelijke switch-cases."}]}
{"phase":"test-rejected"}
<!-- test-feedback:end -->

## Eindsamenvatting

Ik heb alle context: `.task.md`, het volledige worklog en de story-diff. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-642: Integratietests: ontbrekende e2e-scenario's toevoegen

**Wat is gebouwd**

De markdown-e2e-suite (`e2e/scenarios/`, agent-afspeelbare NL-scripts) is uitgebreid met twee nieuwe scenario's voor de tot nu toe ongedekte functionele schermen:

1. **`api-log-scenario.md`** — Instellingen → sectie **Debug** → tegel **"API-log"** → `ApiLogScreen`. Dekt de `API_BASE_URL`-header, de lijst met laatste API-calls (status-badge, methode+URL, tijd/duur), de detail-tap (volledige URL, status, ISO-tijd, "Error / body:" alleen bij fouten) en de AppBar-acties **"Kopieer alles"** (→ snackbar "Gekopieerd") en **"Log wissen"** (→ lege staat), inclusief leeg-/faalcondities.
2. **`admin-scenario.md`** — Instellingen → admin-only sectie **Beheer** → `AdminScreen` (gebruikersbeheer, `/api/admin/users`) en het doorgenavigeerde `AdminCostsScreen` (kosten, `/api/admin/costs/...`: totaal-kaarten Vandaag/Deze maand/Dit jaar/Totaal + tabs Per dag/Per gebruiker/Logboek). Bevat een expliciet **⏭ Skipped**-pad voor een testaccount zonder `ROLE_ADMIN` en een **⚠️ Partial**-pad.

De overzichtstabellen zijn bijgewerkt naar de volledige, actuele set: `e2e/readme.md` (mappenstructuur + opsomming) en `specs/e2e.md` (tabelrij). Hiermee zijn alle via browser-e2e bereikbare Flutter-schermen afgedekt.

**Gemaakte keuzes**

- **Geen productiecode gewijzigd** — uitsluitend `e2e/`, de docs-tabellen en het worklog. De diff bevat geen Kotlin/Flutter-code en geen unit-test-wijzigingen.
- **Geen backend-integratietests** (Cucumber/WireMock/`@SpringBootTest`) opgetuigd: die vereisen een Postgres die in de factory-omgeving ontbreekt; opzetten zou de "alle tests slagen"-randvoorwaarde breken.
- Alle UI-teksten/iconen/AppBar-titels/endpoints zijn 1-op-1 geverifieerd tegen de bron-`.dart`-bestanden in plaats van vrij geformuleerd.

**Wat is getest**

Validatie verliep via code-inspectie (een docs-only PR; een preview-screenshot zou identiek aan `main` zijn). Reviewer en tester hebben elk scenario 1-op-1 tegen `api_log_screen.dart`, `admin_screen.dart`, `admin_costs_screen.dart` en `settings_screen.dart` gelegd. Eén blocker werd gevonden en verholpen: de leeswijzer van `admin-scenario.md` claimde dat **"Maak admin"** een bevestigingsdialoog toont, terwijl het scherm die rol direct toepast (alleen "Maak gewone user" en "Verwijderen" vragen bevestiging). Na de fix is de story op **tested/akkoord** komen te staan; alle acceptatiecriteria zijn voldaan.

**Bewust niet gedaan**

- **adhoc**: geen Flutter-scherm aanwezig → niet via browser-e2e te dekken.
- **Backend-integratietest-infrastructuur**: buiten scope wegens ontbrekende Postgres.
- Bestaande scenario's (events/podcast/settings) niet overgedaan; alleen de overzichten aangevuld.

---
