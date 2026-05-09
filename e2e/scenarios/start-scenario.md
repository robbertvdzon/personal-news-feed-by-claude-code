# Scenario: start

## Doel
Bereid een verse omgeving voor zodat de andere scenario's tegen schone, voorspelbare data kunnen draaien. Maakt een nieuwe e2e-test-user aan, configureert één productieve RSS-feed en twee categorieën die zeker matches gaan opleveren, en triggert de uurlijkse RSS-pipeline.

## Voorwaarden
- Backend draait op `http://localhost:8080` met `ANTHROPIC_API_KEY` gezet (zonder API-key zal stap 5 wel klaar lopen maar krijg je geen echte samenvattingen of feed-selectie).
- Flutter web-app draait op `http://localhost:3000`.

## Stappen

### 1. Verifieer backend
- Ga (via curl of in een nieuwe tab) naar `http://localhost:8080/actuator/health` — verwacht `"status":"UP"`.

### 2. Open de app
- Open `http://localhost:3000` in Chrome.
- Login-scherm verschijnt.

### 3. Maak nieuwe user aan
- Klik **"Account aanmaken"** onderaan.
- Gebruikersnaam: `e2e_<DATETIME-STAMP>` (bv. `e2e_20260509_1432`) — uniek per run zodat scenario's elkaar niet bijten.
- Wachtwoord: `e2etest`
- Klik **"Account aanmaken"**.
- Verwacht: MainShell verschijnt met de Feed-tab actief en leeg.

### 4. Voeg RSS-feed toe
- Tab **"Settings"**.
- Scroll naar het **"RSS-feeds"** blok.
- In het "Nieuwe feed-URL" veld typ je: `https://techcrunch.com/feed/`
- Klik op het **+**-icoon.
- Verwacht: de URL staat in de lijst.

### 5. Voeg twee categorieën toe
TechCrunch publiceert dagelijks tientallen artikelen, vooral over AI en startups — die twee categorieën hebben gegarandeerd matches in de afgelopen 4 dagen.

#### Categorie 1: AI
- Klik **"Categorie toevoegen"**.
- Naam: `AI`
- Klik **"Opslaan"**.
- Klik op de **"AI"** rij om te bewerken.
- In het "Extra instructies" veld:
  > Artikelen over AI, large language models, ChatGPT/Claude/GPT, machine learning, AI-tools en AI-bedrijven. Inclusief releases, funding rounds, en technische diepgang.
- Klik **"Opslaan"**.

#### Categorie 2: Startups
- Klik **"Categorie toevoegen"**.
- Naam: `Startups`
- Klik **"Opslaan"**.
- Klik op de **"Startups"** rij.
- "Extra instructies":
  > Tech-startups, funding rounds, IPOs, acquisities en zakelijke ontwikkelingen in de tech-industrie.
- Klik **"Opslaan"**.

Verwacht: drie categorieën in totaal (AI, Startups, Overig — die laatste is systeem-categorie).

### 6. Trigger uurlijkse update
- Tab **"Queue"**.
- Op de rij **"Uurlijkse RSS-update"** (status `DONE`): klik op de **▶**-knop (`play_arrow`).
- Verwacht: status verandert direct naar `Bezig…` (PROCESSING) met een progress-indicator.
- **Wacht** tot de status weer `Klaar` (DONE) wordt. Dit duurt 1-3 minuten met API-keys actief (afhankelijk van het aantal nieuwe artikelen) en seconden zonder.

In de backend log zie je tijdens deze stap:
```
[RSS] start uurlijkse verwerking voor gebruiker 'e2e_…'
[RSS] stap 1/4: 1 feeds parallel ophalen…
[RSS] N nieuwe artikelen voor 'e2e_…'
[RSS] stap 2/4: AI-samenvatting per artikel…
[RSS] stap 3/4: AI-selectie voor de persoonlijke feed…
[RSS] stap 4/4: uitgebreide feed-samenvattingen genereren…
[RSS] klaar: N nieuwe artikelen, M in feed, duur Xs
```

## Verwacht resultaat

- Queue: `Uurlijkse RSS-update` op status DONE met `newItemCount ≥ 5` (TechCrunch postet meerdere keren per dag, ondergrens is conservatief).
- RSS-tab: ten minste 5 items zichtbaar, sommige met categorie `AI` of `Startups`.
- Feed-tab: één of meer items met `inFeed: true` (mits Claude er minstens één relevant vond) — kan 0 zijn bij strenge prompt of API-budget op; dat is niet fataal voor `start-scenario`, wel een bevinding voor `report.md`.
- Settings: 1 RSS-feed-URL, 3 categorieën (AI, Startups, Overig).

## Notities voor de runner

- De gekozen username moet een fresh user zijn — als hij toevallig al bestaat, krijg je een 409. Verwerk de timestamp in de naam zodat dit niet voorkomt.
- Onthoud de gekozen username voor `cleanup-scenario` aan het eind.
- API-key in de env? Check `[Anthropic] no API key configured` waarschuwingen in de backend log. Geen key = geen feed-selectie en geen uitgebreide samenvattingen.
