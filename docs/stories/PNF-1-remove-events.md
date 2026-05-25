# PNF-1 — Option to remove events

## Stappenplan

- [x] docs/factory/ aanmaken op basis van skeleton-template
- [x] Implementatie verifiëren: DELETE /api/events/{id}
- [x] Implementatie verifiëren: V14 Flyway-migratie (event_denylist)
- [x] Implementatie verifiëren: EventDiscoveryPipeline past denylist toe
- [x] Implementatie verifiëren: prullenbak-icoon in EventDetailScreen
- [x] Developer loopback (ronde 1): functional-spec.md corrigeren n.a.v. reviewer-bevinding
- [x] Developer loopback (ronde 2): Kernfuncties-tabel inconsistentie oplossen
- [x] Developer loopback (ronde 3): gebruikersflow stap 3 hard-delete formulering corrigeren

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

### Developer loopback ronde 1 (reviewer-feedback verwerkt)

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

### Developer loopback ronde 2 (reviewer-feedback verwerkt)

De reviewer signaleerde een interne inconsistentie in de Kernfuncties-tabel:
de Settings-rij vermeldde "denylist beheren" zonder voorbehoud, terwijl
Gebruikersflows stap 5 en het derde acceptatiecriterium expliciet aangaven dat
de denylist-UI nog niet in de frontend bestaat.

**Oplossing**: de Settings-rij in de Kernfuncties-tabel is aangepast:
- Was: `RSS-broncategorieën, event-voorkeuren en denylist beheren`
- Nu: `RSS-broncategorieën en event-voorkeuren beheren; denylist beheren _(toekomstig)_`

### Developer loopback ronde 3 (reviewer-feedback verwerkt)

De reviewer signaleerde dat de inleidende formulering van gebruikersflow stap 3
("markeert het event als verwijderd") een soft-delete impliceert, terwijl de
werkelijke implementatie (`EventServiceImpl.kt:55`) een hard-delete uitvoert van
zowel het feed-item als het event-record, gevolgd door een insert in `event_denylist`.

**Oplossing**: stap 3 in `functional-spec.md` is herschreven naar:
- `DELETE /api/events/{id}` → verwijdert het event hard uit de DB en voegt het
  toe aan de denylist.
- Bullets specificeren nu expliciet "hard-verwijderd" voor beide DB-acties.

## Wat is aangemaakt/gewijzigd in deze PR

- `docs/factory/` compleet ingevuld (README, technical-spec, development,
  functional-spec, deployment, secrets-local, agents/*).
- `docs/factory/functional-spec.md` gecorrigeerd n.a.v. reviewer-feedback (3 rondes).
- `docs/stories/PNF-1-remove-events.md` (dit bestand).

Geen wijzigingen aan bestaande code.
