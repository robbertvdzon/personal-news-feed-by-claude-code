# Scenario: events

## Doel
De Events-tab doorlopen: de lijst met aankomende en geweeste tech-events bekijken, een event-detail openen, en een event verwijderen via het prullenbak-icoon. Verifieer dat de app na verwijderen terugnavigeert naar de lijst en dat het event verdwenen is (en — denylist-gedrag — niet terugkomt bij een nieuwe zoekopdracht). Optioneel: handmatige event-discovery starten.

## Voorwaarden
- `start-scenario` is succesvol gedraaid: ingelogd als `e2e_…`.
- Er moet minstens één event in de lijst staan om de detail-/verwijder-stappen zinvol te doorlopen. Events ontstaan via de wekelijkse Tavily-zoekjob of via de handmatige discovery-knop (stap 1 hieronder).
- `PNF_OPENAI_API_KEY` **en** een Tavily-key actief — event-discovery leunt op Tavily + AI. Zonder die keys (of bij lege Tavily-respons) blijft de lijst leeg: markeer dan als ⚠️ Partial (zie "Graceful degradation").

## Stappen

### 1. (optioneel) Start handmatige event-discovery
- Klik op de **Events**-tab in de bottom navigation (icoon `event`).
- AppBar toont de titel **"Events"** met rechtsboven twee icoon-knoppen: **"Zoek nu naar nieuwe events"** (`travel_explore`) en **"Lijst herladen"** (`refresh`).
- Klik op **"Zoek nu naar nieuwe events"**.
- Verwacht: snackbar **"Event-zoekopdracht gestart — check straks de lijst"**. De discovery draait async op de backend (1-3 min met keys actief).
- Wacht en klik daarna op **"Lijst herladen"** tot er events verschijnen.

> Alternatief: dezelfde job is te starten via Settings → Achtergrond-taken → rij **"Zoek nu naar nieuwe events"** (zie `settings-scenario`); die toont de snackbar **"Event-zoekopdracht gestart — check straks de Events-tab"**.

### 2. Bekijk de lijst
- Verwacht op de Events-tab:
  - Bij events in de toekomst een sectiekop **"Aankomend"** (icoon `upcoming`); bij events in het verleden een sectiekop **"Geweest"** (icoon `history`). Een of beide secties kunnen aanwezig zijn.
  - Aankomende events oplopend op startdatum gesorteerd; geweeste events aflopend.
  - Elke kaart toont: een `event`-icoon, de **event-naam** (vetgedrukt), als subtitel de **datum** (NL-formaat, bv. "17–20 maart 2026") · **locatie** en eventueel de **organisatie**, en rechts een **categorie-chip**.
- Als de lijst leeg is, staat er **"Nog geen events ontdekt"** met de hint over de zoekknop / wekelijkse zoekopdracht → ga naar "Graceful degradation".

### 3. Open een event-detail
- Klik op de eerste event-kaart.
- Verwacht het detailscherm (`event_detail_screen.dart`):
  - AppBar-titel **"Aankomend event"** (voor een toekomstig event) of **"Event"** (voor een geweest event), met rechtsboven een **prullenbak-icoon** (`delete_outline`, tooltip "Verwijderen").
  - De event-naam als headline.
  - Chips: datum (`event`-icoon), eventueel locatie (`place`-icoon), eventueel organisatie (`business`-icoon), en de categorie.
  - Als er een beschrijving is: kop **"Onderwerpen"** met een **selecteerbare** tekst eronder.
  - Eventueel een sectie **"Video's"** (per video een kaart met een **"Maak samenvatting"**-knop of een reeds gegenereerde Nederlandse samenvatting) — alleen als er video's voor dit event ontdekt zijn.
  - Eventueel een sectie **"Bronnen"** met aantikbare links (`link`-icoon).

### 4. Verwijder het event
- Onthoud de naam van dit event.
- Klik op het **prullenbak-icoon** (tooltip "Verwijderen") rechtsboven.
- Verwacht: de app navigeert **direct terug naar de Events-lijst** (pop).
- Verwacht in de lijst: het zojuist verwijderde event is **niet meer zichtbaar**; het totaal aantal kaarten is met één afgenomen.

### 5. Verifieer denylist-gedrag
- Klik nogmaals op **"Zoek nu naar nieuwe events"** (of Settings → "Zoek nu naar nieuwe events") en herlaad de lijst.
- Verwacht: het verwijderde event **komt niet terug** — een verwijderd event wordt op de backend op een denylist gezet en bij een volgende discovery overgeslagen.

## Verwacht resultaat

- De Events-tab toont aankomende en/of geweeste events met correcte sortering, datum-, locatie- en categorie-weergave.
- Een event-detail toont naam, chips, (optioneel) onderwerpen, video's en bronnen; tekst is selecteerbaar.
- Verwijderen via het prullenbak-icoon navigeert terug naar de lijst en haalt het event eruit.
- Het verwijderde event verschijnt niet opnieuw na een nieuwe discovery (denylist).

## Graceful degradation

- ⚠️ **Partial** — lijst blijft leeg ("Nog geen events ontdekt") nadat de discovery is gestart: Tavily/AI-key ontbreekt of de zoekopdracht leverde (nog) geen events op. Noteer in `report.md` als ⚠️ Partial met bevinding "geen events ontdekt — Tavily/AI-output leeg of keys niet actief"; de detail-/verwijder-stappen kunnen dan niet worden uitgevoerd.
- ⚠️ **Partial** — wel events maar geen video's/bronnen: dat is normaal voor events zonder ontdekte video's; de "Video's"- en "Bronnen"-secties zijn dan simpelweg afwezig (geen fout).

## Faal-condities

- ❌ Na klikken op het prullenbak-icoon blijft de app op het detailscherm hangen of toont een foutmelding (verwacht: terug naar lijst).
- ❌ Het verwijderde event staat na terugkeer nog steeds in de lijst (= delete-state niet bijgewerkt).
- ❌ Het verwijderde event verschijnt na een nieuwe discovery weer (= denylist niet toegepast).
- ❌ De lijst verwart aankomend/geweest of sorteert verkeerd.
