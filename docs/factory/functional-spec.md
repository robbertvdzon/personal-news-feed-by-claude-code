# Functional Spec

## Wat doet de app?

Personal News Feed is een AI-gestuurde persoonlijke nieuwsfeed. De backend
bewaakt RSS-feeds, ontdekt tech-events en zoekt YouTube-video's van conferenties.
Claude samenvat alles in het Nederlands. De Flutter-app toont het resultaat als
een leesbare feed.

## Kernfuncties

| Functie | Beschrijving |
|---|---|
| Feed | Persoonlijke nieuwsfeed met samenvattingen van RSS-artikelen en events |
| Events | Ontdekking en weergave van tech-conferenties (JavaOne, KotlinConf, …) |
| Event-videos | YouTube-opnames van keynotes/sessies per event, inclusief NL-samenvatting |
| Podcasts | Podcast-feeds bewaken en aflevering-samenvattingen genereren |
| Settings | RSS-broncategorieën en event-voorkeuren beheren; denylist beheren _(toekomstig)_ |

## Gebruikersflows

### Event verwijderen (PNF-1 / KAN-68)
1. Gebruiker opent een event in `EventDetailScreen`.
2. Tikt op het prullenbak-icoon (rechts in de AppBar).
3. Backend: `DELETE /api/events/{id}` → verwijdert het event hard uit de DB en voegt het toe aan de denylist:
   - Feed-item wordt hard-verwijderd uit de DB.
   - Event-record wordt hard-verwijderd uit de DB en toegevoegd aan `event_denylist` (normalized_id + naam).
4. `EventDiscoveryPipeline` slaat alle denylist-items over bij volgende discovery-run.
5. Het event keert niet terug bij volgende discovery-runs.
   > Backend-endpoints voor denylistbeheer (`GET /api/settings/event-denylist`,
   > `DELETE /api/settings/event-denylist/{normalizedId}`) bestaan, maar een
   > frontend-UI hiervoor is nog niet geïmplementeerd (zie follow-up PNF-2).

## Acceptatiecriteria (structureel)

- Verwijderd event verschijnt niet meer in de Events-lijst.
- Een volgende discovery-run maakt het event niet opnieuw aan.
- _(Niet geïmplementeerd in frontend)_ De backend biedt `GET /api/settings/event-denylist`
  en `DELETE /api/settings/event-denylist/{normalizedId}` voor toekomstige herstel-UI.
