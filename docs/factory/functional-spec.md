# Functional Spec

Personal News Feed (PNF) is een persoonlijke nieuws-aggregator: de app verzamelt
artikelen uit RSS-feeds en podcast-RSS-bronnen, cureert en vat ze samen, en toont ze
in een per-gebruiker gepersonaliseerde feed.

## Belangrijke gebruikersflows

- **Feed lezen** — gecureerde artikelen + dagelijkse samenvatting bekijken, markeren
  (gelezen/geliket/bewaard).
- **Instellingen** (`settings_screen.dart`) — account, weergave, categorieën,
  achtergrond-taken, opruimen en debug/beheer. Langere editor-secties worden naar eigen
  subpagina's verplaatst.
- **RSS-feeds beheren** (`rss_feeds_screen.dart`, subpagina van Instellingen, SF-220) —
  gewone RSS-feed-URL's toevoegen/openen/verwijderen en podcast-RSS-bronnen toevoegen
  (met synchrone URL-validatie + snackbar bij ongeldige URL), de "Transcriberen aan/uit"-
  toggle bedienen en bronnen verwijderen.
- **Events** — wekelijkse event- en video-zoekjobs, handmatig te triggeren vanaf
  Instellingen.

## Terugkerende acceptatiecriteria

- Functionaliteit die op een endpoint leunt, moet ook daadwerkelijk in de Flutter-screens
  zichtbaar zijn (backend-endpoint ≠ frontend-feature); valideer met een grep op de
  betrokken term in `frontend/lib/`.
- Styling/gedrag van nieuwe of verplaatste UI moet consistent zijn met de bestaande app
  (sectiekoppen, list-tiles, loading-spinner, error-tekst "Fout: …", monospace-URL's).
- `flutter analyze` levert geen nieuwe waarschuwingen op; ongebruikte imports worden
  opgeruimd.
