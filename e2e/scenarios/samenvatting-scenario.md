# Scenario: samenvatting

## Doel
De handmatige trigger van de dagelijkse samenvatting testen: via de knop "Genereer dagelijkse samenvatting nu" onder Settings → Achtergrond-taken (intern stuurt die een rerun naar het `daily-summary-…` request), en daarna verifiëren dat het resultaat in de Feed-tab onder de "Samenvatting"-tab verschijnt en netjes als markdown is gerenderd.

## Voorwaarden
- `start-scenario` is succesvol gedraaid: ingelogd als `e2e_…`, RSS-pipeline heeft items + summaries gegenereerd in de afgelopen 24 uur.
- `PNF_ANTHROPIC_API_KEY` actief — anders genereert Claude een lege string en wordt het resultaat een placeholder.

## Stappen

### 1. Open Settings → Achtergrond-taken
- Klik op **Settings** in de bottom navigation en scroll naar de sectie **"Achtergrond-taken"**.
- Verwacht: twee rijen, beide met een **▶ Start**-knop die enabled is:
  - **"RSS-feeds nu vernieuwen"** — triggert `hourly-update-{username}` (na start-scenario op DONE).
  - **"Genereer dagelijkse samenvatting nu"** — triggert `daily-summary-{username}` (status DONE, aangemaakt bij register).
- Verifieer via `GET /api/requests` dat beide fixed records bestaan (`isHourlyUpdate: true` resp. `isDailySummary: true`).

### 2. Trigger de daily summary
- Klik op **▶ Start** in de rij **"Genereer dagelijkse samenvatting nu"**.
- Verwacht direct: knop wordt disabled met tooltip **"Loopt al…"** en toont een kleine `CircularProgressIndicator` in plaats van het play-icoon. Achterliggende status: PENDING → PROCESSING.

In de backend log:
```
[Rerun] daily-summary -> regenerate for 'e2e_…'
[Anthropic] 'generateDailySummaryFromRss' …
[Summary] dagelijkse samenvatting aangemaakt voor 'e2e_…'
```

### 3. Wacht op DONE
- Na 10-30 seconden is de knop weer enabled en — als Settings open blijft — verschijnt een snackbar **"Klaar — 1 items verwerkt"**.
- Onderliggend request: status `DONE`, `newItemCount = 1` (één FeedItem toegevoegd), `costUsd` toont de Anthropic-kosten van de aanroep.

### 4. Open Feed-tab
- Klik op **Feed**.
- Klik op tab **"Samenvatting"**.
- Verwacht: één item met Title beginnend met "Dagelijkse samenvatting" + datum (bv. `Dagelijkse samenvatting 2026-05-09`).
  - Categorie-chip: leeg of "overig".
  - Het item heeft `isSummary: true` → toont een chip "Samenvatting" rechts.

### 5. Open de samenvatting
- Klik op het item.
- Verwacht in detail-scherm:
  - Headline = de samenvatting-titel.
  - Volledige inhoud (600-1000 woorden) gerenderd als **Markdown** met:
    - Headers (`# Onderwerp` als grote tekst).
    - Lijsten (• of 1. 2. 3.).
    - Vet en cursief.
    - Paragrafen met witregels ertussen.
  - Selecteerbaar (cmd/ctrl+c werkt).
  - Geen "Open bron"-knop (samenvatting heeft geen bron-URL — als die wel verschijnt is dat OK, hij wijst dan nergens heen).

### 6. Inhoud-check
- De samenvatting moet refereren aan onderwerpen uit de RSS-items van de afgelopen 24 uur. Concreet: noemt minstens één bron (TechCrunch) of artikel-titel-element.

### 7. Terug + tweede run
- Ga terug naar Settings → Achtergrond-taken.
- Klik **opnieuw** op **▶ Start** in de rij "Genereer dagelijkse samenvatting nu".
- Verwacht: nieuwe call, knop weer disabled (status PROCESSING) → DONE + snackbar.
- In Feed-tab onder "Samenvatting": **nog steeds één item** met dezelfde id `daily-summary-feed-<datum>`. Het oude item is overschreven (zelfde dag = zelfde id).

## Verwacht resultaat

- Trigger via de Settings-knop start de pipeline; status updates komen via WebSocket binnen.
- Het gegenereerde FeedItem heeft `isSummary: true` en is alleen onder de "Samenvatting"-tab te vinden.
- De samenvatting is geldige Nederlandse Markdown met meerdere paragrafen en koppen (~600-1000 woorden).
- Tweede trigger op dezelfde dag overschrijft het bestaande item, geen duplicaten.

## Faal-condities

- ❌ Status blijft op PENDING / hangt: backend pipeline crasht of bereikt Claude niet (check log).
- ❌ Samenvatting is leeg/zegt "Welkom!" placeholder: API-key niet actief of credit op.
- ❌ Markdown wordt niet gerenderd (ruwe `#` en `**` zichtbaar): MarkdownBody niet aangezet voor isSummary-items (regression van een eerdere bug).
- ❌ Tweede run maakt een tweede item ipv overschrijven: feed.delete(id) vóór save mist.
