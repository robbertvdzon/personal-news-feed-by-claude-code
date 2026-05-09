# Scenario: rss

## Doel
Het RSS-overzicht doorlopen: lijst zien, items openen, terugnavigeren, tabs (Alles / Bewaard / categorieën / Overig), filter-switch (Verberg gelezen) en de "Markeer alles als gelezen"-knop. Verifieer dat per-item lees/ster/feedback acties direct in de UI doorkomen en bij refresh blijven hangen.

## Voorwaarden
- `start-scenario` is succesvol gedraaid: ingelogd als `e2e_…`, RSS-feed geconfigureerd, uurlijkse update is `DONE` met items in de lijst.

## Stappen

### 1. Open RSS-tab
- Klik op de **RSS**-tab in de bottom navigation.
- Verwacht: lijst met items (titel + bron + relatieve tijd + categorie-chip + "in feed"/"niet in feed"-badge + 2-regel preview met markdown).

### 2. Verberg-gelezen toggle
- Bovenaan: **Switch "Verberg gelezen"** staat default op **aan**.
- Zet hem **uit** → items met `isRead: true` verschijnen extra (initieel waarschijnlijk geen, want alle items zijn pas binnen).
- Zet hem weer **aan** → ongelezen-only.

### 3. Tabs
- Klik tab **"Alles"** → telt alle items, alle items zichtbaar.
- Klik tab **"Bewaard"** → leeg (nog niets gesternd).
- Klik elke categorie-tab op rij (**AI**, **Startups**, …) → telt en toont alleen items met die categorie. Bolletje-aantal moet matchen met wat zichtbaar is.
- Klik **"Overig"** als laatste tab → items met categorie `overig` of zonder.
- Verwacht: **één tab tegelijk actief** (onderstreping + primary kleur).

### 4. Item-detail
- Ga terug naar tab **"Alles"**.
- Klik op het eerste item.
- Verwacht in detail-scherm:
  - Headline = titel van het artikel (Engels uit RSS).
  - Chips: bron, categorie, datum.
  - **Banner** "In persoonlijke feed" (groen, met Claude's reden) of "Niet in persoonlijke feed" (oranje, met fallback-tekst).
  - Topic-chips eronder als die er zijn.
  - **Markdown-gerenderde** Nederlandse AI-samenvatting (vet/cursief netjes opgemaakt, geen ruwe `**`).
  - **AppBar-icoontjes**: 👍 👎 ⭐ ✉️.

### 5. Acties op het item
- Klik 👍-icoon in AppBar → wordt groen.
- Klik nogmaals → terug naar grijs (toggle naar `null`).
- Klik ⭐-icoon → wordt amber.
- Klik ✉️-icoon (markeer als ongelezen, want het werd automatisch op gelezen gezet bij openen).
- Verwacht: iconen reageren onmiddellijk (optimistic update).

### 6. Browse door items
- Swipe naar links of rechts (PageView) → volgende/vorige item.
- Bij elk item dat in beeld komt wordt automatisch `isRead: true` gezet.
- Doe dit voor 3-5 items.

### 7. Terug naar lijst
- Klik **←** (terugknop in AppBar).
- Verwacht: in de lijst zijn de items die je net hebt gezien grijs/normaal-gewicht (gelezen-styling). De gesternde is gemarkeerd met ⭐.

### 8. Verberg-gelezen herhaald
- Switch **"Verberg gelezen"** is nog steeds aan → de items die je hebt gelezen zijn nu niet meer zichtbaar.
- Tab-tellers zouden moeten zijn afgenomen met het aantal gelezen items.

### 9. Markeer alles als gelezen
- AppBar: klik **"done_all"**-icoon (Markeer alles als gelezen).
- Bevestigingsdialog → klik **"Alles als gelezen"**.
- Verwacht: groene snackbar **"N RSS-items als gelezen aangemerkt."** waarbij N = aantal nog ongelezen items vóór de actie.
- Lijst is leeg (alles gelezen, hide-read aan) of tab-bolletjes staan op 0/leeg.
- Zet "Verberg gelezen" UIT → alle items komen weer terug met gelezen-styling.

### 10. Lijst-herlaad
- Klik 🔄-icoon in AppBar (Lijst herladen).
- Verwacht: lijst wordt herladen vanaf de backend. Gelezen-state blijft (kwam via API binnen).

## Verwacht resultaat

- Tabs filteren correct, telbolletjes kloppen met zichtbare aantallen.
- Item-detail toont titel/banner/topics/markdown-summary, AppBar-knoppen reflecteren live state.
- 👍/👎/⭐/✉️ acties werken optimistisch én blijven na refresh.
- Verberg-gelezen werkt; "Markeer alles als gelezen" toont groene success-snackbar met aantal.

## Faal-condities

- ❌ Tab-bolletjes tonen geen aantal (= waarschijnlijk telling-bug).
- ❌ Acties komen wel direct door maar verdwijnen na refresh (= API-call faalt — check backend log).
- ❌ Snackbar bij "alles gelezen" is **rood** met "Server kon de actie niet uitvoeren" → backend draait niet de laatste versie of endpoint mist.
- ❌ Detail-scherm toont ruwe `**markdown**` i.p.v. opgemaakte tekst → MarkdownBody niet aangezet.
