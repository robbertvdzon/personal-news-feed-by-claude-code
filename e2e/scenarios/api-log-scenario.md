# Scenario: api-log

## Doel
De **API-log** (debug-scherm) doorlopen: open hem via Instellingen → Debug, controleer de
lijst met laatste API-calls, tik een entry aan voor de detailweergave (volledige URL, status,
tijd/duur en eventuele error-body), en test de AppBar-acties **"Kopieer alles"** en
**"Log wissen"**.

## Voorwaarden
- `start-scenario` is succesvol gedraaid: ingelogd als `e2e_…`. Door het inloggen en de
  start-acties zijn er al een aantal API-calls gelogd (de `ApiLog` vult zich client-side bij
  elke request), dus de lijst is normaal gesproken **niet leeg**.
- Geen backend-AI-keys nodig: de API-log toont alleen de HTTP-calls die de Flutter-app zelf doet.

## Stappen

### 1. Open de API-log
- Open de **Instellingen**-tab (icoon `settings`, rechtsonder), AppBar-titel **"Instellingen"**.
- Scroll naar de sectie **"Debug"**.
- Tik op de tegel **"API-log"** (icoon `bug_report_outlined`, subtitel
  **"Laatste calls + status (voor debugging)"**, `chevron_right`).
- Verwacht: een nieuw scherm met AppBar-titel **"API-log"** en rechts twee actie-iconen:
  **"Kopieer alles"** (`copy_all`) en **"Log wissen"** (`delete_outline`).

### 2. Controleer de header en de lijst
- Bovenaan staat een grijze header met label **"API_BASE_URL"** en daaronder de actieve
  base-URL in monospace (bv. `http://localhost:8080`) — selecteerbaar.
- Daaronder een lijst met de laatste API-calls. Elke rij toont:
  - links een gekleurd **status-badge** (groen voor 2xx, rood/`ERR` bij fout),
  - de **methode + URL** in monospace (bv. `GET /api/feed`), max 2 regels,
  - een subtitel met **tijd** (`uu:mm:ss`) en **duur** (`…ms`).
- Verwacht: minstens één entry (de calls uit `start-scenario`, zoals `GET /api/feed`,
  `GET /api/categories`).

### 3. Open een entry (detailweergave)
- Tik op een willekeurige entry in de lijst.
- Verwacht een dialoog met:
  - titel **"<METHODE>  <statuscode>"** (bv. `GET  200`, of `… ERR` bij fout),
  - de **volledige URL** in monospace (selecteerbaar),
  - een regel met **ISO-tijdstip + duur in ms**,
  - alleen bij een fout-call het blok **"Error / body:"** met de response-/fouttekst,
  - knoppen **"Kopieer"** (kopieert methode/status/URL/error naar klembord en sluit) en
    **"Sluiten"**.
- Sluit de dialoog met **"Sluiten"**.

### 4. "Kopieer alles"
- Tik in de AppBar op **"Kopieer alles"** (`copy_all`).
- Verwacht: een snackbar **"Gekopieerd"**. (Het klembord bevat nu één regel per gelogde call;
  klembordinhoud is in de browser-e2e niet altijd te inspecteren — de snackbar is de check.)

### 5. "Log wissen"
- Tik in de AppBar op **"Log wissen"** (`delete_outline`).
- Verwacht: de lijst is direct leeg en toont de placeholder-tekst
  **"Nog geen calls gelogd. Trigger een actie in de app en kom terug."**
  De `API_BASE_URL`-header blijft staan.

### 6. Log opnieuw vullen (controle dat wissen écht client-side was)
- Ga terug naar Instellingen (terugknop) en open een andere tab (bv. Feed) zodat er een nieuwe
  API-call gebeurt; open daarna de API-log opnieuw.
- Verwacht: er staan weer entries in de lijst (de log vult zich opnieuw bij nieuwe calls).

## Verwacht resultaat
- De API-log is bereikbaar via Instellingen → Debug → "API-log" en toont de `API_BASE_URL` plus
  een lijst met de laatste calls (methode/URL/status/tijd/duur).
- Een entry aantikken opent een detaildialoog met de volledige URL, status, tijd/duur en — bij
  fouten — de error-body, met knoppen "Kopieer"/"Sluiten".
- **"Kopieer alles"** geeft een snackbar **"Gekopieerd"**.
- **"Log wissen"** maakt de lijst leeg (placeholder-tekst verschijnt); na een nieuwe call vult de
  log zich opnieuw.

## Graceful degradation
- ⚠️ **Partial** — direct na het openen van de app kan de lijst (kortstondig) leeg zijn als er nog
  geen calls zijn gelogd; trigger dan eerst een actie (tab wisselen) en kom terug. De placeholder
  **"Nog geen calls gelogd…"** is dan zelf het te verifiëren UI-element.
- ⚠️ **Partial** — er is geen enkele fout-call beschikbaar, waardoor het **"Error / body:"**-blok
  in stap 3 niet getoond kan worden; de overige detailvelden (URL, status, tijd/duur) zijn wél te
  bevestigen.

## Faal-condities
- ❌ De tegel "API-log" ontbreekt onder Debug of opent niet (= navigatie kapot).
- ❌ "Kopieer alles" geeft geen snackbar **"Gekopieerd"**.
- ❌ "Log wissen" laat de lijst onveranderd staan (= clear-actie werkt niet).
- ❌ Een entry aantikken opent geen detaildialoog, of de getoonde URL/status komt niet overeen
  met de rij.
