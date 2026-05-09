# Scenario: feed

## Doel
Het persoonlijke feed-overzicht doorlopen: lijst zien, items openen, terugnavigeren, tabs (Alles / Bewaard / Samenvatting / categorieën), filter-switch en de "Markeer alles als gelezen"-knop. Verifieer dat de Feed-items een Nederlandse `titleNl` + 2-regel `shortSummary` tonen, en dat de detail-pagina een uitgebreide markdown-rendering heeft.

## Voorwaarden
- `start-scenario` is succesvol gedraaid en de uurlijkse pipeline heeft minstens één item op `inFeed: true` gezet (te zien in de RSS-tab als groene "in feed"-badge).
- Als 0 items in feed staan: dit scenario kan niet zinvol draaien — markeer als ⚠️ Partial in `report.md` met als bevinding "Claude rejecteerde alle items, prompt-tuning nodig".

## Stappen

### 1. Open Feed-tab
- Klik op **Feed** in de bottom navigation.
- Verwacht: lijst met FeedItems. Elke kaart toont:
  - **Titel:** korte Nederlandse `titleNl` (~70 chars).
  - **Bron · X uur geleden** (relatieve tijd uit `createdAt`).
  - Categorie-chip + datum.
  - **2-regel preview** = `shortSummary` (Nederlandse plain-text teaser).

### 2. Tabs
- Default-tab is **"Alles"** (eerste).
- Klik tab **"Bewaard"** → leeg (nog niets gesternd in deze run).
- Klik tab **"Samenvatting"** → leeg (nog geen daily-summary, dat doet `samenvatting-scenario`).
- Klik op een categorie-tab waar items in zitten (bv. **"AI"**) → tellertje moet kloppen met zichtbare items.
- Klik **"Alles"** weer.

### 3. Verberg-gelezen
- Switch staat op aan.
- Zet uit → eventueel gelezen items verschijnen ook (initieel allemaal ongelezen na de refresh).
- Zet aan.

### 4. Item-detail
- Klik op het eerste item.
- Verwacht in detail-scherm:
  - Headline = `titleNl` (Nederlands).
  - **Daaronder klein cursief grijs**: het originele Engelse `title` (alleen als die afwijkt — voor automatische items zal dat zo zijn).
  - Chips: bron, categorie, datum.
  - Volledige `summary` (400-600 woorden) **als Markdown** gerenderd: vet/cursief opgemaakt, paragrafen, eventueel kopjes.
  - **Tekst is selecteerbaar** (cmd/ctrl+c werkt).
  - "Open bron"-knop onderaan, niet onder de gesture-bar.
  - AppBar-icoontjes: 👍 👎 ⭐ ✉️.

### 5. Acties + browse
- 👍 → groen, ⭐ → amber.
- Swipe horizontaal door 2-3 items.
- Bij elk item: bekijk de markdown-rendering (test verschillende artikellengtes).
- Terug.

### 6. Terug op de lijst
- Items die je hebt geopend zijn grijs/gelezen.
- De gesternde toont een ⭐ in de actie-rij.
- Switch je naar tab **"Bewaard"** → de gesternde verschijnt daar.

### 7. Markeer alles als gelezen
- AppBar: klik **"done_all"**-icoon.
- Bevestigingsdialog → klik **"Alles als gelezen"**.
- Verwacht: groene snackbar **"N feed-items als gelezen aangemerkt."**.
- Tab-bolletjes vallen naar 0 (met hide-read aan).

### 8. Refresh
- Klik 🔄-icoon → lijst herlaadt vanaf backend.
- Gelezen-state blijft.

## Verwacht resultaat

- Lijst toont Nederlandse titles + 2-regel shortSummary, niet ruwe Engelse content.
- Detail-scherm toont titleNl als headline, originele titel klein eronder, lange markdown summary mooi opgemaakt en selecteerbaar.
- Acties werken optimistisch én via de API; persistent na refresh.
- Tabs (Alles/Bewaard/Samenvatting/cat) filteren correct met telbolletjes.
- Mark-all-read snackbar is **groen** met aantal.

## Faal-condities

- ❌ Lijst-titels in het Engels (= legacy items zonder `titleNl` of generator-bug).
- ❌ Lijst-preview toont 600 woorden i.p.v. 2 regels (= shortSummary leeg, fallback faalt om te truncaten).
- ❌ Detail-summary toont ruwe `**vet**` strings (= MarkdownBody niet ingezet).
- ❌ Mark-all-read rode snackbar (zie rss-scenario voor diagnose).
