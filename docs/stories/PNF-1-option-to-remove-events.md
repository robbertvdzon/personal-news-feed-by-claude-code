# PNF-1: Option to remove events

## Story

Gebruiker wil events kunnen verwijderen. Verwijderd event mag niet opnieuw worden aangemaakt (denylist), en er mogen geen video's meer voor worden gezocht.

## Stappen

- [x] Controleer of implementatie al aanwezig is in codebase
- [x] Maak `docs/factory/` aan op basis van skeleton-template

## Bevindingen

De volledige implementatie was al aanwezig vanuit eerdere tickets (KAN-65 t/m KAN-68):

- `DELETE /api/events/{id}` endpoint in `EventController`
- `EventServiceImpl.delete()` verwijdert event hard + gekoppeld `FeedItem`
- `event_denylist` tabel (Flyway migratie V14) voorkomt herontdekking
- `EventDiscoveryPipeline` controleert denylist vóór opslaan
- `EventVideoDiscoveryPipeline` laadt via `events.load()` — hard-deleted events staan er niet meer in
- Frontend prullenbak-icoon in `EventDetailScreen`
- OpenAPI spec gedocumenteerd in `specs/openapi.yaml`

## Gedaan in deze PR

Aangemaakt `docs/factory/` directory met alle skeleton-bestanden, ingevuld met concrete repo-informatie:
- `README.md`, `development.md`, `technical-spec.md`, `functional-spec.md`, `deployment.md`, `secrets-local.md`
- `agents/developer.md`, `agents/refiner.md`, `agents/reviewer.md`, `agents/tester.md`
- `docs/stories/.gitkeep`
