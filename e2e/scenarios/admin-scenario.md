# Scenario: admin

## Doel
De admin-only **Beheer**-schermen doorlopen: gebruikersbeheer (`AdminScreen`,
`/api/admin/users`) en het kosten-overzicht (`AdminCostsScreen`, `/api/admin/costs/…`).
Het scenario gaat **graceful ⏭ Skipped** wanneer het e2e-testaccount geen admin-rol heeft —
de sectie **Beheer** is dan simpelweg afwezig in Instellingen.

## Voorwaarden
- `start-scenario` is succesvol gedraaid: ingelogd als `e2e_…`.
- **Admin-rechten vereist.** De sectie **Beheer** in Instellingen is alleen zichtbaar als het
  ingelogde account `ROLE_ADMIN` heeft (`auth.isAdmin`). Een vers via "Account aanmaken"
  gemaakt e2e-account is een **gewone user** → dan is dit scenario **⏭ Skipped** (zie
  "Skipped-pad" onderaan). Het is alleen volledig te draaien met een testaccount dat al admin is.

## Stappen

### 0. Bepaal of Beheer beschikbaar is
- Open de **Instellingen**-tab. Scroll naar onderen, voorbij de sectie **"Debug"** (API-log).
- **Als** er géén sectie **"Beheer"** staat → het account is geen admin → markeer dit scenario
  als **⏭ Skipped** en stop hier (zie "Skipped-pad").
- **Als** er wél een sectie **"Beheer"** staat (kop **"Beheer"**), met de tegels
  **"Beheer gebruikers"** (`person_outline`) en **"Beheer kosten"** (`attach_money`), ga verder.

### 1. Gebruikersbeheer (`AdminScreen`)
- Tik op de tegel **"Beheer gebruikers"** (`chevron_right`).
- Verwacht: nieuw scherm met AppBar-titel **"Admin"** en rechts de acties
  **"Kosten-overzicht"** (`payments`) en **"Lijst herladen"** (`refresh`).
- De body toont een lijst gebruikers (uit `/api/admin/users`), gesorteerd op gebruikersnaam.
  Per rij:
  - een rond avatar-icoon (`admin_panel_settings` voor admins, anders `person`),
  - de **gebruikersnaam**; bij het eigen account een kleine **"jij"**-chip,
  - de subtitel **"Rol: admin"** of **"Rol: user"**,
  - rechts een **drie-puntjes-menu** (popup).
- Open het menu bij het **eigen** e2e-/admin-account. Verwacht de optie **"Wachtwoord resetten"**.
  - Het eigen account toont **geen** "Verwijderen" en (als admin) geen "Maak admin"-optie.
- Open het menu bij een **andere** gebruiker. Verwacht (afhankelijk van diens rol):
  **"Wachtwoord resetten"**, **"Maak admin"** (bij een gewone user) of **"Maak gewone user"**
  (bij een admin, niet jezelf), en **"Verwijderen"** (rood).
- **Niet-destructief blijven:** sluit het menu zonder een wijziging te bevestigen (tik buiten het
  menu of kies "Annuleren" in een eventuele dialoog). Voer **geen** rol-wijziging, wachtwoord-reset
  of verwijdering door op echte accounts.
  > Optioneel/leeswijzer: "Wachtwoord resetten" opent een dialoog "Nieuw wachtwoord voor <user>"
  > (min. 4 tekens, knoppen "Annuleren"/"Resetten"); "Maak admin"/"Maak gewone user" en
  > "Verwijderen" vragen een bevestigingsdialoog ("Bevestig", knoppen "Annuleren"/"Doorgaan").
  > Annuleer deze altijd in de e2e-run.

### 2. Kosten-overzicht (`AdminCostsScreen`)
- Tik in de AppBar van het Admin-scherm op **"Kosten-overzicht"** (`payments`).
  (Alternatief: vanuit Instellingen → Beheer → tegel **"Beheer kosten"** opent hetzelfde scherm.)
- Verwacht: nieuw scherm met AppBar-titel **"Kosten"** en een actie **"Vernieuwen"** (`refresh`).
- Bovenaan vier totaal-kaarten: **"Vandaag"**, **"Deze maand"**, **"Dit jaar"** en **"Totaal"**
  (de laatste met een subtitel **"N calls"**) — bedragen in USD (uit `/api/admin/costs/totals`).
- Daaronder drie tabs: **"Per dag"**, **"Per gebruiker"** en **"Logboek"**.

#### 2a. Tab "Per dag"
- Verwacht een tabel met kolommen **Datum / Totaal / OpenAI / ElevenLabs / Tavily / Calls**
  (uit `/api/admin/costs/daily?days=30`).
- Bij geen enkele geregistreerde call: de tekst **"Nog geen externe calls geregistreerd"**.

#### 2b. Tab "Per gebruiker"
- Bovenaan keuze-chips voor de periode: **"Deze maand"**, **"Vorige maand"**, **"Dit jaar"**,
  **"Alles"** (default **"Deze maand"**) — schakelt `/api/admin/costs/by-user?period=…`.
- Tabel met kolommen **Gebruiker / Totaal / OpenAI / ElevenLabs / Tavily / Calls**.
- Bij geen calls in de periode: **"Geen calls in deze periode"**.

#### 2c. Tab "Logboek"
- Bovenaan filter-chips **"Alle providers"**, **"Alle statussen"**, **"Alle acties"** (elk opent
  een keuzedialoog) — schakelt `/api/admin/costs/calls?…`.
- Lijst met per call: provider-initiaal (O/E/T/R/W), **"<actie> · <gebruiker>"**, tijd/duur en
  het bedrag in USD; fout-calls zijn rood. Bij geen resultaten: **"Geen calls"**.
- Tik op **"Vernieuwen"** (`refresh`) in de AppBar → de totalen en tabbladen herladen.

## Verwacht resultaat
- De sectie **Beheer** is alleen aanwezig bij een admin-account; bij een gewone user is dit
  scenario **⏭ Skipped**.
- `AdminScreen` (AppBar **"Admin"**) toont de gebruikerslijst uit `/api/admin/users` met
  rol-labels, de "jij"-chip op het eigen account en een per-user actiemenu; destructieve acties
  worden in de e2e-run alleen bekeken/geannuleerd, niet uitgevoerd.
- `AdminCostsScreen` (AppBar **"Kosten"**) toont de totaal-kaarten (Vandaag/Deze maand/Dit
  jaar/Totaal) en de drie tabs (Per dag/Per gebruiker/Logboek) op basis van
  `/api/admin/costs/{totals,daily,by-user,calls}`; lege staten tonen de juiste placeholders.

## Skipped-pad (geen admin-rechten)
- ⏭ **Skipped** — het e2e-testaccount is een gewone user (`auth.isAdmin == false`): de sectie
  **Beheer** ontbreekt in Instellingen, dus stap 1–2 zijn niet uitvoerbaar. Dit is **verwacht
  gedrag**, geen fout. Noteer in `report.md`: *"admin-scenario ⏭ Skipped — testaccount heeft geen
  ROLE_ADMIN; sectie Beheer afwezig (correct)."*
- ⚠️ **Partial** — admin-account aanwezig maar de backend heeft nog geen externe calls
  geregistreerd: de Beheer-navigatie en de gebruikerslijst zijn te verifiëren, maar de
  kosten-tabbladen tonen hun lege-staat-placeholders i.p.v. data. Markeer als ⚠️ Partial.

## Faal-condities
- ❌ De sectie **Beheer** verschijnt bij een **niet-admin** account (= `auth.isAdmin`-gate omzeild).
- ❌ `AdminScreen` laadt niet (foutmelding **"Fout: …"** i.p.v. de gebruikerslijst) terwijl het
  account admin is (= `/api/admin/users` faalt — check backend log).
- ❌ Het eigen account toont een **"Verwijderen"**-optie (= self-delete-guard ontbreekt).
- ❌ `AdminCostsScreen` toont een fout i.p.v. de totaal-kaarten/tabs terwijl er een admin-sessie is
  (= `/api/admin/costs/…` faalt).
