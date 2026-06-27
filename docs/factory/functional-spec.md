# Functional Spec — Personal News Feed

## Wat doet de app?

Een persoonlijke AI-nieuwsfeed die:
- RSS-feeds ophaalt en met AI samenvat en categoriseert
- Artikelen selecteert voor een persoonlijke feed op basis van gebruikersinteresses
- Ad-hoc zoekverzoeken verwerkt op een opgegeven onderwerp
- Dagelijks een AI-samenvatting genereert
- Podcasts genereert (script + audio) op basis van recente nieuwsartikelen
- Tech-events ontdekt en bijhoudt (Tavily + OpenAI)
- Multi-user: elke gebruiker heeft volledig eigen data en instellingen

## Gebruikersflows

### Feed
- Gebruiker ziet gecureerde feed-items geselecteerd door AI op basis van leesgedrag, likes en sterren.
- Gebruiker kan een feed-item liken, sterren of feedback geven.

### RSS
- Gebruiker configureert RSS-feed URLs in Settings.
- De backend haalt feeds periodiek op, maakt AI-samenvattingen en selecteert relevante items.

### Events
- Gebruiker ziet een lijst van aankomende en afgelopen tech-events ontdekt via Tavily + OpenAI.
- Gebruiker kan in Settings handmatig een event- en video-zoekopdracht starten.
- Gebruiker kan een event verwijderen (prullenbak-icoon in EventDetailScreen).
- Verwijderd event wordt toegevoegd aan de `event_denylist` — de discovery pipeline slaat het daarna stilzwijgend over.
- Denylist is per gebruiker: twee gebruikers kunnen hetzelfde event onafhankelijk beheren.

### Podcasts
- Gebruiker vraagt een podcast aan; de backend genereert een script en audio (twee stemmen).
- Gebruiker kan de podcast afspelen vanuit de app.

### Settings
- Gebruiker beheert categorie-instellingen en RSS-/podcast-feeds.
- Gebruiker kan handmatig de event- en video-discovery starten en de dagelijkse samenvatting laten genereren.
- Let op: de event-voorkeuren (`event_preferences`) en de event-denylist worden in de huidige frontend **niet** via een Settings-UI beheerd; ze bestaan alleen als backend-endpoints (`/api/settings/event-preferences`, `/api/settings/event-denylist`). De denylist wordt gevuld door een event te verwijderen; voorkeuren krijgen bij eerste gebruik sensible defaults.

## Acceptatiecriteria

### PNF-2 — Events verwijderen

- [x] `DELETE /api/events/{id}` verwijdert het event en het bijbehorende FeedItem.
- [x] Het event-ID wordt toegevoegd aan `event_denylist` (per user).
- [x] Bij de volgende discovery-run wordt het verwijderde event niet opnieuw aangemaakt.
- [x] De discovery pipeline doet geen Tavily/OpenAI-calls voor events op de denylist.
- [x] In EventDetailScreen is een prullenbak-icoon zichtbaar in de AppBar.
- [x] Na verwijderen navigeert de app terug naar de events-lijst.
- [x] Denylist is per-user geïsoleerd.
