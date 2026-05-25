# PNF-1 — Option to remove events

## Stappenplan

- [x] docs/factory/ aanmaken op basis van skeleton-template
- [x] Implementatie verifiëren: DELETE /api/events/{id}
- [x] Implementatie verifiëren: V14 Flyway-migratie (event_denylist)
- [x] Implementatie verifiëren: EventDiscoveryPipeline past denylist toe
- [x] Implementatie verifiëren: prullenbak-icoon in EventDetailScreen
- [x] Developer loopback: functional-spec.md corrigeren n.a.v. reviewer-bevinding

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

### Developer loopback (reviewer-feedback verwerkt)

De reviewer signaleerde dat `functional-spec.md` een acceptatiecriterium bevatte
dat niet overeenkomt met de werkelijke implementatie: "Settings toont de denylist
zodat gebruiker een event kan herstellen." De Flutter `settings_screen.dart`
bevat geen denylist-UI (grep geeft nul hits op `denylist` in `frontend/`).

Backend-endpoints bestaan wel (`GET /api/settings/event-denylist` en
`DELETE /api/settings/event-denylist/{normalizedId}` in `SettingsController.kt`).

**Oplossing**: `functional-spec.md` is aangepast:
- Gebruikersflow stap 5 beschrijft nu wat er wél is (geen herhaling via Settings-UI),
  met een notitie dat de backend-endpoints klaarstaan voor een toekomstige frontend.
- Het derde acceptatiecriterium is omgeschreven naar de feitelijke situatie
  (backend-endpoints aanwezig, frontend-UI nog niet geïmplementeerd).

## Wat is aangemaakt/gewijzigd in deze PR

- `docs/factory/` compleet ingevuld (README, technical-spec, development,
  functional-spec, deployment, secrets-local, agents/*).
- `docs/factory/functional-spec.md` gecorrigeerd n.a.v. reviewer-feedback.
- `docs/stories/PNF-1-remove-events.md` (dit bestand).

Geen wijzigingen aan bestaande code.
