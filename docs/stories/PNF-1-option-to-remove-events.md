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

Done / rationale:
- Developer-run gestart: story en factory-docs gelezen zodat het plan in de target-repo zichtbaar is.
- Claude developer-run is afgerond. De factory heeft de branch gepusht en de PR geopend of hergebruikt.

Commit geslaagd. 12 bestanden aangemaakt.

---

## Samenvatting

De story PNF-1 ("Option to remove events") was al volledig geïmplementeerd in de codebase vanuit eerdere tickets (KAN-65–68). De enige openstaande taak was het aanmaken van `docs/factory/` op basis van de skeleton-template.

## Gedaan

- `docs/factory/README.md` — projectoverzicht en index
- `docs/factory/development.md` — build- en testcommando's voor backend (Maven) en frontend (Flutter/Make), codeconventies
- `docs/factory/technical-spec.md` — tech stack, Spring Modulith modules, architectuurregels, bekende valkuilen
- `docs/factory/functional-spec.md` — kernfunctionaliteit, event-lifecycle, acceptatiecriteria
- `docs/factory/deployment.md` — OpenShift/ArgoCD flow, preview-deploys, secrets-beheer (met factory-YAML frontmatter)
- `docs/factory/secrets-local.md` — alle benodigde env-vars voor lokaal draaien
- `docs/factory/agents/{developer,refiner,reviewer,tester}.md` — rol-specifieke agent-instructies
- `docs/stories/.gitkeep` en `docs/stories/PNF-1-option-to-remove-events.md` — story-log

## Niet gedaan / aangepast

- Geen codewijzigingen — de implementatie (delete-endpoint, denylist, frontend prullenbak-knop) was al aanwezig.
- Claude developer-run is afgerond. De factory heeft de branch gepusht en de PR geopend of hergebruikt.

Commit geslaagd.

---

## Samenvatting

Reviewer-feedback voor PNF-1 verwerkt: twee cosmetische bevindingen opgelost in één commit.

## Gedaan

- **`docs/stories/PNF-1-option-to-remove-events.md`**: Twee raw JSON-artefacten verwijderd die achterbleven aan het einde van het bestand (`agent_tips_update` en `phase`-object).
- **`docs/factory/agents/tester.md`**: Story-specifieke noot verwijderd ("Voor event-delete stories: ...") die snel zou verouderen in een generiek agent-instructiebestand.

## Niet gedaan / aangepast

- Commit-bericht `be633d6` ("PNF-1: AI developer changes") is niet gewijzigd — dat is historische git-history op een gedeelde branch, niet veilig te herschrijven zonder afstemming.
- Geen codewijzigingen — de story-implementatie zelf was al compleet.

{"agent_tips_update":[{"category":"repo-conventions","key":"story-log-no-json-artifacts","content":"Laat geen raw agent_tips_update of phase JSON-objecten achter in docs/stories/*.md bestanden. Reviewer in PNF signaleert dit consequent als [suggestie]. Verwijder deze artefacten voor de commit."}]}
