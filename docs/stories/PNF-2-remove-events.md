# PNF-2 — Option to remove events from the list

## Stappenplan

- [x] Lees .task.md en factory-docs skeleton
- [x] Controleer bestaande implementatie in de repo
- [x] Maak `docs/factory/` aan met alle skeleton-bestanden
- [x] Maak `docs/stories/PNF-2-remove-events.md` aan
- [x] Commit lokaal

## Bevindingen

De volledige functionaliteit gevraagd in PNF-2 was al geïmplementeerd in de repo:

| Component | Locatie |
|-----------|---------|
| `DELETE /api/events/{id}` endpoint | `EventController.kt:94-98` |
| `event_denylist` tabel (per user, soft-delete) | `V14__event_preferences_and_denylist.sql:30-36` |
| Denylist-check in discovery pipeline | `EventDiscoveryPipeline.kt` (commentaar regel 44-45) |
| Prullenbak-icoon in EventDetailScreen (Flutter) | `event_detail_screen.dart:21-29` |
| OpenAPI spec bijgewerkt | `specs/openapi.yaml` |

## Wat gedaan

Aangemaakt: volledige `docs/factory/`-map op basis van de skeleton-template in `/usr/local/share/factory/docs-skeleton/`, gevuld met concrete repo-informatie (stack, modulestructuur, secrets, deployment, agent-instructies).

Bestanden aangemaakt:
- `docs/factory/README.md`
- `docs/factory/technical-spec.md`
- `docs/factory/functional-spec.md`
- `docs/factory/development.md`
- `docs/factory/deployment.md`
- `docs/factory/secrets-local.md`
- `docs/factory/agents/developer.md`
- `docs/factory/agents/reviewer.md`
- `docs/factory/agents/refiner.md`
- `docs/factory/agents/tester.md`
- `docs/stories/.gitkeep`
- `docs/stories/PNF-2-remove-events.md`

Geen code gewijzigd — de PNF-2 functionaliteit was al volledig aanwezig.
