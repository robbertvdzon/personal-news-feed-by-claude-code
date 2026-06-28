# Scenario: settings

## Doel
De Instellingen-tab doorlopen voor wat daadwerkelijk in de UI zit: categorieën beheren (toevoegen en bewerken), de RSS-/podcast-feeds-editor (`rss_feeds_screen.dart`) en de Achtergrond-taken-knoppen (RSS vernieuwen, dagelijkse samenvatting, event- en video-discovery). Verifieer dat wijzigingen direct in de UI doorkomen.

## Voorwaarden
- `start-scenario` is succesvol gedraaid: ingelogd als `e2e_…`, één RSS-feed en de categorieën AI/Startups/Overig aanwezig.
- De Achtergrond-taken-knoppen die echte AI/Tavily-jobs starten (RSS-refresh, daily summary, event-/video-discovery) leunen op `PNF_OPENAI_API_KEY` / Tavily; zonder die keys lopen ze wel maar zonder zinvolle output (zie "Graceful degradation").

## Stappen

### 1. Open de Instellingen-tab
- Klik op de **instellingen**-tab in de bottom navigation (icoon `settings`, rechtsonder).
- AppBar-titel **"Instellingen"**.
- Verwacht (van boven naar beneden) de secties: **Over deze app** (frontend-/backend-versie), **Account** (gebruikersnaam, "Uitloggen", "Wachtwoord wijzigen"), **Weergave** ("Grote tekst"-switch), **Categorieën**, **RSS-feeds**, **Achtergrond-taken**, **Opruimen**, **Debug** (API-log), en alleen voor admins **Beheer**.

### 2. Categorieën beheren
- Scroll naar de sectie **"Categorieën"**.
- Verwacht: een aan/uit-switch per categorie (AI, Startups, Overig). De systeem-categorie **"Overig"** toont subtitel **"Systeem"** en heeft **geen** bewerk-icoon; AI en Startups hebben rechts een **potlood-icoon** (`edit`).

#### 2a. Categorie toevoegen
- Klik **"Categorie toevoegen"** (`add`-icoon, onderaan de lijst).
- Dialoog **"Nieuwe categorie"** → veld **"Naam"**: typ `E2E-test`.
- Klik **"Opslaan"**.
- Verwacht: de rij **"E2E-test"** verschijnt in de lijst met een eigen aan/uit-switch en potlood-icoon.

#### 2b. Categorie bewerken
- Klik op het **potlood-icoon** van **"E2E-test"**.
- Dialoog **"Categorie: E2E-test"** met velden **"Naam"** en **"Extra instructies"** (max 3 regels), plus knoppen **"Verwijderen"** (rood), **"Annuleren"** en **"Opslaan"**.
- Vul bij "Extra instructies" bv. in: `Test-categorie voor de e2e-suite.` en klik **"Opslaan"**.
- Open de rij nogmaals → de ingevoerde extra instructies zijn bewaard.

#### 2c. (opruimen) Test-categorie verwijderen
- Open **"E2E-test"** opnieuw via het potlood-icoon → klik **"Verwijderen"**.
- Verwacht: de rij **"E2E-test"** verdwijnt; de oorspronkelijke categorieën (AI, Startups, Overig) blijven staan.

### 3. RSS-/podcast-feeds-editor
- Scroll naar **"RSS-feeds"** en tik op de tegel **"RSS-feeds"** (subtitel "RSS-feeds en podcast-bronnen beheren", `chevron_right`).
- Verwacht een nieuw scherm `RssFeedsScreen` (AppBar **"RSS-feeds"**) met twee blokken: **"RSS-feeds"** en **"Podcast-bronnen"**.

#### 3a. RSS-feeds
- Het blok toont de bestaande feed-URL(s) (monospace), elk met een **×**-icoon om te verwijderen.
- Onderaan een veld **"Nieuwe feed-URL"** (hint `https://...`) met een **+**-icoon.
- Typ een URL (bv. `https://www.theverge.com/rss/index.xml`) en klik **+** → de URL verschijnt in de lijst.
- Klik op het **×** naast die net toegevoegde URL → hij verdwijnt weer (laat de TechCrunch-feed uit `start-scenario` staan).

#### 3b. Podcast-bronnen
- Het blok **"Podcast-bronnen"** toont eventuele podcast-RSS-bronnen met per bron een **"Transcriberen aan/uit"**-switch en een **×**-icoon.
- Onderaan een veld **"Nieuwe podcast-RSS-URL"** met een **+**-icoon. Toevoegen valideert de URL **synchroon op de server** (spinner tijdens valideren).
- Verwacht bij een **geldige** podcast-RSS-URL: de bron verschijnt in de lijst met "Transcriberen aan".
- Verwacht bij een **ongeldige** URL: binnen ~10s een **rode snackbar** met een Nederlandse foutmelding (bv. "Kon feed niet ophalen"), en de URL wordt **niet** toegevoegd.
- Ga terug naar Instellingen (terugknop).

### 4. Achtergrond-taken
- Scroll naar **"Achtergrond-taken"**. Verwacht vier rijen, elk met een **▶ Start**-knop:
  - **"RSS-feeds nu vernieuwen"** (`rss_feed`-icoon) — triggert `hourly-update-{username}`.
  - **"Genereer dagelijkse samenvatting nu"** (`summarize`-icoon) — triggert `daily-summary-{username}`.
  - **"Zoek nu naar nieuwe events"** (`event`-icoon) — start de event-discovery.
  - **"Zoek nu naar event-video's"** (`video_library`-icoon) — start de video-discovery.
- Klik op **▶ Start** bij **"RSS-feeds nu vernieuwen"**.
  - Verwacht: de knop wordt **disabled** met label **"Bezig…"** + spinner en tooltip **"Loopt al…"**; bij voltooien een snackbar **"Klaar — N items verwerkt"** (als Instellingen open blijft).
- Klik op **▶ Start** bij **"Zoek nu naar nieuwe events"**.
  - Verwacht: knop kort disabled met **"Bezig…"** + spinner, daarna snackbar **"Event-zoekopdracht gestart — check straks de Events-tab"**.
- Klik op **▶ Start** bij **"Zoek nu naar event-video's"**.
  - Verwacht: snackbar **"Video-zoekopdracht gestart — check straks de events"**.

> De "Genereer dagelijkse samenvatting nu"-knop wordt apart in `samenvatting-scenario` uitgediept; hier volstaat dat de rij + ▶ Start-knop aanwezig en enabled zijn.

## Verwacht resultaat

- Een categorie toevoegen, bewerken (incl. extra instructies) en verwijderen werkt en is direct zichtbaar; "Overig" blijft een niet-bewerkbare systeem-categorie.
- De RSS-feeds-editor voegt feed-URL's toe en verwijdert ze; de podcast-bronnen-editor valideert URL's serverseitig (geldige → toegevoegd, ongeldige → rode snackbar).
- De vier Achtergrond-taken-knoppen starten hun job met de juiste snackbar/spinner-feedback.

## Graceful degradation

- ⚠️ **Partial** — geen `PNF_OPENAI_API_KEY` / Tavily-key: de Achtergrond-taken starten wél (snackbar verschijnt) maar leveren geen of lege output. De UI-feedback (spinner, snackbar) is alsnog te verifiëren; noteer de ontbrekende output als ⚠️ Partial-bevinding.
- ⚠️ **Partial** — geen werkende voorbeeld-URL voor een geldige podcast-bron: de validatie-pad voor een geldige bron (3b, eerste verwacht) kan dan niet bevestigd worden; de ongeldige-URL-snackbar is wél te testen.

## Faal-condities

- ❌ "Overig" toont een bewerk-icoon of is uitschakelbaar als ware het een gewone categorie (= systeem-flag genegeerd).
- ❌ Een toegevoegde categorie/feed-URL verschijnt niet in de lijst na opslaan (= save-call faalt — check backend log).
- ❌ Een ongeldige podcast-RSS-URL wordt zonder foutmelding toegevoegd (= serverseitige validatie omzeild).
- ❌ Een Achtergrond-taken-knop blijft permanent op "Bezig…" hangen (= status-poll/rerun kapot).
