# Scenario: rss-podcast

## Doel
Het dedicated **RSS-podcast-detailscherm** (`rss_podcast_detail_screen.dart`,
`RssPodcastDetailScreen`) doorlopen: het scherm dat opent wanneer je een
**podcast-aflevering** (`mediaType = PODCAST`) aantikt in de RSS- of Feed-lijst.
Dit is een ander scherm dan `RssItemDetailScreen` (artikelen) en dan
`PodcastDetailScreen` (de zelf-gegenereerde AI-podcasts uit `podcast-scenario`).
We verifiëren de drie podcast-specifieke secties — **Samenvatting**, **Key
takeaways** en het inklapbare **Ruw transcript** — plus de **vertaal-flow**
("🇳🇱 Vertaal & genereer Nederlandse podcast" / "Bekijk vertaling"). De
vertaal-actie is **kostengenererend** en wordt in deze e2e-run **alleen bekeken,
nooit bevestigd** (annuleren in de bevestigingsdialoog).

## Voorwaarden
- `start-scenario` is succesvol gedraaid: ingelogd als `e2e_…`.
- Er staat **minstens één podcast-aflevering** in de RSS-lijst. Die ontstaat door
  in de RSS-/podcast-feeds-editor een **podcast-bron** toe te voegen (zie
  `settings-scenario`, stap 3b) en daarna de RSS-pipeline te draaien
  (Achtergrond-taken → "RSS-feeds nu vernieuwen"). Podcast-afleveringen worden in
  de RSS-tab herkenbaar getoond met het `podcasts`-icoon.
- Geen extra keys nodig om het scherm te **bekijken**. De daadwerkelijke
  vertaling (die we hier niet bevestigen) leunt op `PNF_OPENAI_API_KEY`.
- Is er geen enkele podcast-aflevering beschikbaar (geen podcast-bron of de bron
  leverde nog geen afleveringen op): markeer dit scenario als **⏭ Skipped** (zie
  "Graceful degradation").

## Stappen

### 1. Open een podcast-aflevering vanuit de RSS-lijst
- Open de **RSS**-tab.
- Zoek een kaart die een **podcast-aflevering** is (leading `podcasts`-icoon /
  een aflevering van een toegevoegde podcast-bron). Tik die kaart aan.
- Verwacht: een nieuw scherm met een AppBar-titel in de vorm **"`<n>/<totaal>`"**
  (positie in de podcast-paginering — alleen podcast-afleveringen zitten in deze
  swipe-context, geen artikelen). Rechts in de AppBar dezelfde actie-iconen als
  bij artikelen: **Vind ik leuk** (`thumb_up`), **Niet relevant** (`thumb_down`),
  **Bewaar** (`star`) en **Markeer als (on)gelezen** (`mark_email_read`).
- Bij openen wordt de aflevering automatisch op **gelezen** gezet (net als bij
  artikelen).

### 2. Header
- Bovenaan een **`podcasts`-icoon** + de afleveringstitel (headline).
- Daaronder chips: **bron**, **categorie**, eventueel **"`<N>` min"** (duur) en
  eventueel de **publicatiedatum**.
- Is de samenvatting nog op de RSS-show-notes gebaseerd (transcript nog niet
  verwerkt), dan staat er een amberkleurige chip **"voorlopig"**
  (`hourglass_top`, tooltip "Voorlopige samenvatting op basis van de RSS
  show-notes. Het echte transcript wordt op de achtergrond verwerkt.").

### 3. Sectie "Samenvatting"
- Kop **"Samenvatting"** met daaronder een als **Markdown** gerenderde,
  selecteerbare Nederlandse samenvatting (~400-600 woorden, meerdere alinea's).
- Is de uitgebreide samenvatting nog niet gegenereerd, dan toont de sectie de
  korte samenvatting/snippet **plus** de cursieve grijze hint
  **"Uitgebreide samenvatting wordt op de achtergrond verwerkt."** — dat is
  verwacht gedrag, geen fout.

### 4. Sectie "Key takeaways"
- Indien aanwezig: kop **"Key takeaways"** met een **bullet-lijst** (5-10 punten,
  één regel per bullet). De bullet-tekst is **selecteerbaar**.
- Heeft de aflevering nog geen takeaways (b.v. nog-niet-verwerkte aflevering),
  dan is deze sectie **afwezig** (niet getoond) — verwacht, geen fout.

### 5. Sectie "Ruw transcript" (inklapbaar, lazy-loaded)
- Een ingeklapte **ExpansionTile** met titel **"Ruw transcript"** en subtitel
  **"Volledige Whisper-output (lang)"**.
- Klap hem **open**: pas bij het openklappen wordt het transcript via een aparte
  call (`GET /api/rss/<id>/transcript`) opgehaald — verwacht eerst een
  **spinner**, daarna de **selecteerbare** transcript-tekst in een grijs blok.
- Mogelijke nette eindstaten (alle geen fout):
  - **Geen transcript**: tekst **"Geen transcript beschikbaar voor deze
    aflevering."** (cursief).
  - **Show-notes-aflevering** (`summarySource = show_notes`): in plaats van de
    open/klap-tegel een grijs blok **"Transcript wordt nog verwerkt."**
    (`hourglass_top`).
- Klap de tegel weer **dicht** (het transcript blijft client-side gecachet, dus
  opnieuw openklappen doet geen nieuwe HTTP-call).

### 6. Vertaal-sectie — **niet-destructief bekijken**
- Onder de secties staat de vertaal-knop, afhankelijk van de status van de
  bron-aflevering (uit de `EpisodeLookup`-call):
  - **Transcript nog niet klaar** → knop **"🇳🇱 Vertaal & genereer Nederlandse
    podcast"** is **uitgegrijsd** (tooltip "Vertalen kan pas wanneer het Engelse
    transcript klaar is."). Niets te doen.
  - **Transcript klaar, nog geen vertaling** → knop **"🇳🇱 Vertaal & genereer
    Nederlandse podcast"** is **actief**.
  - **Vertaling loopt al** → knop **"🇳🇱 Bekijk vertaling — `<fase>`"** met een
    spinner (fase: "in wachtrij…/vertalen…/audio genereren…").
  - **Vertaling klaar** → knop **"🇳🇱 Bekijk vertaling"** (`translate`-icoon).
- **Als de "Vertaal & genereer…"-knop actief is:** tik hem aan om de
  **bevestigingsdialoog** te bekijken — titel **"🇳🇱 Vertaal & genereer
  Nederlandse podcast"** met de bron, het aantal transcript-tekens, een
  **geschatte kosten**-blok (Vertaling + TTS + Totaal in USD) en knoppen
  **"Annuleren"** / **"Starten"**.
  - **Kies "Annuleren".** Bevestig de vertaling in de e2e-run **niet** — dit is
    een kostengenererende achtergrond-job. (Bij "Starten" zou een snackbar
    "Vertaling gestart — …" verschijnen; dat pad laten we bewust ongetest om geen
    OpenAI-kosten te maken.)
- **Als er al een vertaling bestaat** ("Bekijk vertaling"): die knop opent het
  reguliere `PodcastDetailScreen` (zelfde scherm als in `podcast-scenario`). Je
  mág hem aantikken om de navigatie te bevestigen en daarna terugkeren; dit is
  niet-destructief (alleen openen).

### 7. "Origineel afspelen" en terug
- Onderaan, als de aflevering een bron-URL heeft, een knop **"Origineel
  afspelen"** (`play_circle_outline`) die de aflevering in een externe
  speler/tab opent. Optioneel aantikken; in de e2e-run volstaat het bestaan van
  de knop.
- Swipe eventueel horizontaal naar een **volgende podcast-aflevering** (PageView)
  — de paging-context bevat alleen podcasts, dus je landt nooit op een artikel.
- Ga terug naar de RSS-lijst (terugknop). De geopende aflevering is nu
  **gelezen** (grijs); een eventueel gezette ster/feedback is zichtbaar.

## Verwacht resultaat
- Een podcast-aflevering aantikken opent `RssPodcastDetailScreen` (AppBar
  **"`<n>/<totaal>`"**), niet het artikel- of het AI-podcast-detailscherm.
- De secties **Samenvatting** (Markdown, selecteerbaar), **Key takeaways**
  (bullets, indien aanwezig) en het inklapbare **Ruw transcript** (lazy-loaded,
  selecteerbaar) worden correct getoond; ontbrekende delen vallen netjes terug op
  hun placeholder-tekst.
- De vertaal-sectie toont de juiste knop-staat voor de transcript-/vertaal-status
  en de bevestigingsdialoog toont bron, tekens en geschatte kosten; de
  vertaling-zelf wordt in de e2e-run **niet** gestart (alleen geannuleerd/bekeken).
- Lezen/ster/feedback en terugnavigatie gedragen zich als bij het reguliere
  RSS-detailscherm.

## Graceful degradation
- ⏭ **Skipped** — geen enkele podcast-aflevering in de lijst (geen podcast-bron
  toegevoegd of de bron leverde nog niets op): het scherm is dan niet te openen.
  Noteer in `report.md`: *"rss-podcast-scenario ⏭ Skipped — geen podcast-bron/
  -afleveringen beschikbaar."* Voeg desgewenst eerst een podcast-bron toe via
  `settings-scenario` stap 3b.
- ⚠️ **Partial** — wel een aflevering, maar nog vroeg in de verwerking: de
  uitgebreide samenvatting/takeaways ontbreken (placeholders) en/of het
  transcript is er nog niet ("Geen transcript…"/"Transcript wordt nog verwerkt.").
  Het scherm en de aanwezige secties zijn dan wél te verifiëren; markeer als
  ⚠️ Partial met de bevinding.

## Faal-condities
- ❌ Een podcast-aflevering aantikken opent het **artikel**-detailscherm
  (`RssItemDetailScreen`) i.p.v. `RssPodcastDetailScreen` (= podcast-routing
  kapot, `isPodcast`-check genegeerd).
- ❌ De Markdown-samenvatting toont ruwe `**`/`#`-tekens (= MarkdownBody niet
  ingezet).
- ❌ Het openklappen van "Ruw transcript" toont permanent een spinner of een
  foutmelding terwijl er een transcript zou moeten zijn (= transcript-call faalt —
  check backend `/api/rss/<id>/transcript`).
- ❌ De "Vertaal & genereer…"-knop start de vertaling **zonder** bevestigingsdialoog
  (= bevestigingsstap omzeild, ongewenste kosten).
