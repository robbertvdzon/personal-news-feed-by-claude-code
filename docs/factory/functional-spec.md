# Functional Spec

## Wat doet de app

Personal News Feed is een zelf-gehoste persoonlijke nieuwslezer voor één of meer gebruikers, elk met volledig eigen data en instellingen.

### Kernfunctionaliteit

| Feature | Beschrijving |
|---------|-------------|
| RSS-feed aggregatie | Haalt RSS-feeds op; AI maakt Nederlandstalige samenvatting en wijst categorie toe per artikel |
| Persoonlijke feed | AI selecteert meest relevante artikelen op basis van gebruikersgedrag (likes, sterren, leesgedrag) |
| Ad-hoc zoekopdrachten | Gebruiker geeft onderwerp op → AI zoekt en vat actuele artikelen samen (Tavily-websearch) |
| Dagelijks nieuwsoverzicht | AI genereert automatisch dagelijks een samenvatting van het nieuws |
| Podcasts | AI genereert script + audio (interview-format, twee stemmen) op basis van recente artikelen |
| Event-management | Gebruiker kan events toevoegen, bekijken en verwijderen; denylist voorkomt herontdekking |
| Multi-user | Elke gebruiker heeft eigen data, feeds en instellingen |

### Event-lifecycle

1. Events worden ontdekt via `EventDiscoveryPipeline` (periodiek).
2. Video's voor events worden gezocht via `EventVideoDiscoveryPipeline`.
3. Gebruiker kan een event verwijderen via de prullenbak-knop in `EventDetailScreen`.
4. Verwijderd event komt niet meer terug (denylist-mechanisme via `event_denylist` tabel).
5. Gebruiker kan via Instellingen een event van de denylist verwijderen om herontdekking toe te staan.

### Gebruikersinstellingen

- RSS-feed URLs toevoegen/verwijderen.
- Categorieën configureren (welke categorieën AI moet toewijzen).
- Denylist beheren (verwijderde events inzien en herstellen).

## Acceptatiecriteria (terugkerend)

- Verwijderd event verschijnt niet opnieuw in de event-lijst na een discovery-run.
- Verwijderd event genereert geen verdere video-searches.
- Prullenbak-knop is zichtbaar op `EventDetailScreen`; na bevestiging verdwijnt het event direct.
- Via Instellingen kan een event van de denylist worden verwijderd.
- Alle API-endpoints zijn gedocumenteerd in `specs/openapi.yaml`.

## Gebruikersflows (primair)

1. **Feed lezen**: openen app → feed-tab → artikel selecteren → lezen of liken.
2. **Ad-hoc zoekopdracht**: zoektab → onderwerp invullen → AI-resultaat lezen.
3. **Podcast beluisteren**: podcast-tab → afspelen.
4. **Event verwijderen**: events-tab → event openen → prullenbak-icoon → bevestigen.
5. **Instellingen**: settings-tab → RSS-feeds beheren / denylist inzien.
