# Functional Spec — Personal News Feed

## Wat doet de app?

Een persoonlijke AI-nieuwsfeed die:
- RSS-feeds ophaalt en met AI samenvat en categoriseert
- Artikelen selecteert voor een persoonlijke feed op basis van gebruikersinteresses
- Ad-hoc zoekverzoeken verwerkt op een opgegeven onderwerp
- Dagelijks een AI-samenvatting genereert
- Podcasts genereert (script + audio) op basis van recente nieuwsartikelen
- Multi-user: elke gebruiker heeft volledig eigen data en instellingen

## Gebruikersflows

### Feed
- Gebruiker ziet gecureerde feed-items geselecteerd door AI op basis van leesgedrag, likes en sterren.
- Gebruiker kan een feed-item liken, sterren of feedback geven.

### RSS
- Gebruiker configureert RSS-feed URLs in Settings.
- De backend haalt feeds periodiek op, maakt AI-samenvattingen en selecteert relevante items.

### Podcasts
- Gebruiker vraagt een podcast aan; de backend genereert een script en audio (twee stemmen).
- Gebruiker kan de podcast afspelen vanuit de app.

### Settings
- Gebruiker beheert categorie-instellingen en RSS-/podcast-feeds.
- Gebruiker kan handmatig de RSS-refresh en de dagelijkse samenvatting laten genereren.

## Acceptatiecriteria

_(De events-feature is met SF-1746 volledig verwijderd; de bijbehorende PNF-2-acceptatiecriteria zijn daarmee vervallen.)_
