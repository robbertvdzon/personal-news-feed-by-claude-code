# personal-news-feed-by-claude-code

Een zelf-gehoste, persoonlijke nieuwslezer met AI-curation, podcastgeneratie en multi-user ondersteuning.

## Wat doet de app?

- Haalt RSS-feeds op en laat AI per artikel een Nederlandstalige samenvatting maken
- Selecteert automatisch de meest relevante artikelen voor jouw persoonlijke feed, op basis van je leesgedrag, likes en sterren
- Verwerkt ad-hoc zoekopdrachten: geef een onderwerp op en de AI zoekt en vat actuele artikelen samen
- Genereert dagelijks een AI-nieuwsoverzicht
- Genereert podcasts (script + audio) op basis van recente nieuwsartikelen, in een interview-format met twee stemmen
- Ontdekt tech-events (Tavily + AI) en laat je events verwijderen (denylist)
- Ondersteunt meerdere gebruikers, elk met volledig eigen data en instellingen

## Opbouw

| Map | Inhoud |
|-----|--------|
| `specs/` | Specificaties (zie hieronder) |
| `newsfeedbackend/` | Spring Boot backend (Kotlin, Maven) |
| `frontend/` | Flutter app — volledige client (iOS, Android, web) |
| `frontend-reader/` | Flutter app — read-only reader-variant |

## Hoe dit gebouwd is

Deze app is **spec-first** gebouwd samen met [Claude Code](https://claude.ai/claude-code).

De specificaties in de `specs/` map zijn het vertrekpunt: alle functionaliteit, architectuur en het API-contract zijn eerst beschreven voordat er code is geschreven. Claude Code genereert en onderhoudt de code op basis van die specificaties.

### Specificaties

| Bestand | Inhoud |
|---------|--------|
| [`specs/README.md`](./specs/README.md) | Instapdocument voor de specs |
| [`specs/openapi.yaml`](./specs/openapi.yaml) | Volledige REST API definitie (source of truth) |
| [`specs/backend-functional-spec.md`](./specs/backend-functional-spec.md) | Wat de backend doet: gedrag, pipelines, externe systemen |
| [`specs/backend-technical-spec.md`](./specs/backend-technical-spec.md) | Hoe de backend gebouwd is: Spring Modulith, lagen, monitoring, tests |
| [`specs/frontend-spec.md`](./specs/frontend-spec.md) | Alle schermen, navigatie en functionaliteit van de Flutter app |

## Technologie

**Backend:** Spring Boot 4.x · Kotlin · Maven · Spring Modulith · Poort 8080

**Database:** PostgreSQL (Neon) · Flyway-migraties

**Frontend:** Flutter · Dart · Riverpod

**AI:** OpenAI (samenvatting, selectie, podcast, events, transcriptie/TTS) · Tavily (websearch) · ElevenLabs (podcast audio)

## Ontwikkelen

**Nieuw op dit project?** Begin met [`docs/onboarding-senior-developer.md`](./docs/onboarding-senior-developer.md) — architectuur, de waarom-keuzes, teststrategie en review-checklist. Operationele zaken (deployen, database, secrets, lokaal draaien) staan in [`runbook.md`](./runbook.md).

Backend-tests: `mvn test` (snel, unit) en `mvn verify` (volledige e2e-suite tegen een echte Postgres via Testcontainers; Docker vereist).

Een gecontaineriseerde dev-omgeving met de complete toolchain (JDK 21, Maven, Flutter, Android SDK, Node, Claude Code) is voorbereid voor wie z'n laptop schoon wil houden of z'n setup tussen machines wil delen. Zie [`.devcontainer/README.md`](./.devcontainer/README.md) voor de bouw-, start- en gebruiks-instructies.
<!-- gitops-final-test: tijdelijk, direct gesloten -->
