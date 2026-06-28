# Scenario: podcast

## Doel
De Podcast-tab doorlopen: de lijst met (gegenereerde) podcasts bekijken, een nieuwe podcast aanmaken via de "Nieuwe podcast"-knop, de generatie-status volgen, een afgeronde podcast in detail openen (`podcast_detail_screen.dart`) en het afspelen verifiëren (speler, play/pause, skip-knoppen, draaiboek).

## Voorwaarden
- `start-scenario` is succesvol gedraaid: ingelogd als `e2e_…`, en de RSS-pipeline heeft items + samenvattingen van de afgelopen dagen gegenereerd (de podcast wordt opgebouwd uit dat nieuws).
- `PNF_OPENAI_API_KEY` actief — de podcast-generatie (script + TTS) leunt op OpenAI. Zonder key faalt of stagneert de generatie: markeer dan als ⚠️ Partial (zie "Graceful degradation").

## Stappen

### 1. Open de Podcast-tab
- Klik op de **Podcast**-tab in de bottom navigation (icoon `podcasts`).
- AppBar-titel **"Podcast"** met rechtsboven een **refresh**-icoon.
- Rechtsonder een uitklap-knop **"Nieuwe podcast"** (`add`-icoon).
- Verwacht: bestaande podcasts als kaarten, of **"Nog geen podcasts"** als de lijst leeg is.

### 2. Maak een nieuwe podcast aan
- Klik op **"Nieuwe podcast"**.
- Verwacht een dialoog **"Nieuwe podcast"** met:
  - Veld **"Onderwerpen (één per regel, optioneel)"** — laat leeg om automatisch te kiezen.
  - Veld **"Periode (dagen)"** — default **7** (hint "Aantal dagen aan nieuws dat wordt meegenomen").
  - Veld **"Duur (minuten)"** — default **15** (hint "Gewenste lengte van de podcast in minuten").
  - Dropdown TTS-provider: **"OpenAI TTS"** (default) of **"ElevenLabs"**.
  - Knoppen **"Annuleren"** en **"Maak"** (de "Maak"-knop is alleen enabled bij periode ≥ 1 én duur ≥ 1).
- Zet de duur eventueel op een korte waarde (bv. `5`) om de generatie te bekorten, kies **"OpenAI TTS"** en klik **"Maak"**.

### 3. Volg de generatie-status
- Verwacht: bovenaan de lijst verschijnt een nieuwe kaart met een **spinner** als leading-icoon en een statuslabel dat doorloopt: **"In wachtrij…"** → **"Onderwerpen bepalen…"** → **"Script schrijven…"** → **"Audio genereren…"** → **"Klaar"**.
- Het scherm pollt elke ~4 seconden zelf de status (geen handmatige refresh nodig).
- Wacht tot het statuslabel **"Klaar"** is (1-3 min met key actief). Bij **"Mislukt"** (rood `error`-icoon) → zie "Graceful degradation".

### 4. Open de podcast in detail
- Klik op de afgeronde kaart.
- Verwacht in `podcast_detail_screen.dart`:
  - AppBar-titel = podcast-titel.
  - Onder de titel chips: statuslabel (**"Klaar"**), **"<N> min"** en de TTS-provider (bv. **"OPENAI"**).
  - Eventueel onderwerp-chips.
  - Een **audiospeler**: een voortgangs-slider met links de huidige positie en rechts de totale duur (`mm:ss`).
  - Een **play/pause-knop** (grote gevulde knop) plus skip-knoppen **−60 / −30 / −15 / +15 / +30 / +60** seconden.
  - Knoppen **"Draaiboek"** (`article`-icoon) en **"Download"** (`download`-icoon).

### 5. Verifieer afspelen
- Klik op de **play**-knop.
- Verwacht: de knop wordt een **pause**-knop, de slider-positie loopt op en de positie-tijd links telt door.
- Klik op **+30** → de positie springt ~30s vooruit; klik op **−15** → ~15s terug.
- Klik **pause** → het afspelen stopt, de positie blijft staan.
- Klik op **"Draaiboek"** → er schuift een bottom-sheet open met de **selecteerbare** scripttekst van de podcast; sluit die weer.

### 6. (optioneel) Terug en lijst-check
- Ga terug naar de lijst (terugknop).
- Verwacht: de aangemaakte podcast staat als afgeronde kaart in de lijst met `podcasts`-icoon, titel en "Duur: <N>min · TTS: <provider>".

## Verwacht resultaat

- Een nieuwe podcast wordt aangemaakt via de "Nieuwe podcast"-dialoog en doorloopt zichtbaar de statussen tot **"Klaar"**.
- Het detailscherm toont titel, status/duur/TTS-chips, onderwerpen en een werkende audiospeler.
- Play/pause, skip-knoppen en de slider werken; "Draaiboek" toont het script.
- De afgeronde podcast blijft in de lijst staan.

## Graceful degradation

- ⚠️ **Partial** — generatie blijft hangen op een tussenstatus of eindigt op **"Mislukt"**: `PNF_OPENAI_API_KEY` ontbreekt/credit op, of de TTS-provider faalt. Noteer in `report.md` als ⚠️ Partial met de zichtbare foutmelding (op de detailpagina toont een mislukte podcast een rood foutblok met `errorMessage`). De afspeel-stappen (4-5) kunnen dan niet worden uitgevoerd.
- ⚠️ **Partial** — geen RSS-nieuws van de afgelopen periode: de podcast kan inhoudelijk leeg/dun zijn; dat is geen afspeel-fout maar wel een bevinding voor `report.md`.

## Faal-condities

- ❌ De "Nieuwe podcast"-dialoog laat "Maak" toe bij periode 0 of duur 0 (= validatie kapot).
- ❌ Statuslabel blijft op "In wachtrij…" zonder ooit te pollen/updaten (= status-poll kapot, geen backend-voortgang).
- ❌ Detailpagina toont een DONE-podcast maar zonder speler/slider (= audio-URL laadt niet).
- ❌ Play-knop reageert niet of de positie loopt niet (= audio niet geladen — check backend `/api/podcasts/<id>/audio`).
