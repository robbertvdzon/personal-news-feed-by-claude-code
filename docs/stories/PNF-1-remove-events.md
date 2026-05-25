# PNF-1 — Option to remove events

## Stappenplan

- [x] docs/factory/ aanmaken op basis van skeleton-template
- [x] Implementatie verifiëren: DELETE /api/events/{id}
- [x] Implementatie verifiëren: V14 Flyway-migratie (event_denylist)
- [x] Implementatie verifiëren: EventDiscoveryPipeline past denylist toe
- [x] Implementatie verifiëren: prullenbak-icoon in EventDetailScreen

## Wat is gedaan

De feature "events verwijderen" was reeds volledig geïmplementeerd als KAN-68.
Verificatie bevestigt:

- **DELETE /api/events/{id}** aanwezig in `EventController.kt`.
  `EventServiceImpl.delete()` verwijdert het feed-item, gooit het event uit de
  DB en voegt het toe aan de `event_denylist` via `settings.addEventToDenylist()`.

- **V14-migratie** (`V14__event_preferences_and_denylist.sql`) maakt de tabellen
  `event_preferences` en `event_denylist` aan.

- **EventDiscoveryPipeline** laadt bij iedere run de denylist en slaat
  denylist-items over (`if (raw.id in denylist) { rejectedDenylisted++; continue }`).

- **Frontend** (`event_detail_screen.dart`, regel 24) toont `Icons.delete_outline`
  als `IconButton` in de AppBar; tikt men erop dan roept de Riverpod-notifier
  `eventsProvider.notifier.delete(event.id)` aan.

## Wat is aangemaakt in deze PR

- `docs/factory/` compleet ingevuld (README, technical-spec, development,
  functional-spec, deployment, secrets-local, agents/*).
- `docs/stories/PNF-1-remove-events.md` (dit bestand).

Geen wijzigingen aan bestaande code.
