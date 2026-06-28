# SF-643 — E2e-scenario's API-log en Admin toevoegen + tabellen/worklog bijwerken

Parent story: SF-642 (nightly: Integratietests: ontbrekende scenario's toevoegen).

## Stappenplan

- [x] UI-teksten/iconen/navigatie verifiëren in `api_log_screen.dart`, `admin_screen.dart`, `admin_costs_screen.dart` en de Debug-/Beheer-secties in `settings_screen.dart`.
- [x] `e2e/scenarios/api-log-scenario.md` toevoegen (conventie Doel/Voorwaarden/Stappen/Verwacht resultaat, NL, `start-scenario` als voorwaarde).
- [x] `e2e/scenarios/admin-scenario.md` toevoegen incl. ⏭ Skipped/⚠️ Partial-pad zonder admin-rechten.
- [x] `e2e/readme.md` bijwerken (mappenstructuur + "één of meer scenario's"-opsomming).
- [x] `specs/e2e.md` bijwerken (tabelrij met de volledige scenario-set).
- [x] Worklog `docs/stories/worklog/SF-642-worklog.md` bijwerken.
- [x] Review-stap: consistentie tussen scenario's, tabellen en feitelijke UI-teksten.

## Wat is gedaan en waarom

Story SF-642 vraagt de e2e-suite uit te breiden met de functionele schermen die nog niet
gedekt waren: de **API-log** (Instellingen → Debug) en de admin-only **Beheer**-schermen
(gebruikersbeheer + kosten). De suite bestaat uit menselijk/agent-afspeelbare markdown-scripts
(geen testframework, zie `e2e/readme.md`), dus "tests schrijven" = de scenario's schrijven
volgens de bestaande conventie.

Alle UI-teksten en iconen in de twee nieuwe scenario's zijn 1-op-1 geverifieerd tegen de
Flutter-bron:
- `frontend/lib/screens/api_log_screen.dart`: AppBar-titel "API-log", acties `copy_all`
  ("Kopieer alles" → snackbar "Gekopieerd") en `delete_outline` ("Log wissen"), header met
  `API_BASE_URL`, lege-staat-tekst, detaildialoog met URL/tijd/duur + "Error / body:" en knoppen
  "Kopieer"/"Sluiten".
- `frontend/lib/screens/admin_screen.dart`: AppBar-titel **"Admin"**, acties "Kosten-overzicht"
  (`payments`) en "Lijst herladen" (`refresh`), per-user popupmenu (Wachtwoord resetten / Maak
  admin / Maak gewone user / Verwijderen), "jij"-chip, `/api/admin/users`.
- `frontend/lib/screens/admin_costs_screen.dart`: AppBar-titel **"Kosten"**, totalen-kaarten
  Vandaag/Deze maand/Dit jaar/Totaal, tabs "Per dag"/"Per gebruiker"/"Logboek",
  `/api/admin/costs/{totals,daily,by-user,calls}`.
- `frontend/lib/screens/settings_screen.dart`: sectie **Debug** met tegel "API-log", en de
  admin-only sectie **Beheer** met tegels "Beheer gebruikers" → `AdminScreen` en "Beheer kosten"
  → `AdminCostsScreen` (alleen zichtbaar als `auth.isAdmin`).

Geen productiecode of bestaande unit-tests gewijzigd; uitsluitend bestanden onder `e2e/`,
`specs/e2e.md` en de story-/worklog-bestanden.
