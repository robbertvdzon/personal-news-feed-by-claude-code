# Factory Docs — Personal News Feed

Personal News Feed is een zelf-gehoste, persoonlijke nieuwslezer met AI-curation. De app haalt RSS-feeds op, laat AI artikelen samenvatten en selecteren, verwerkt ad-hoc zoekopdrachten, genereert dagelijkse nieuwsoverzichten en podcasts.

## Architectuur

```
Flutter app (frontend/)
      │
      │  REST (JWT Bearer)      WebSocket
      ▼                              ▼
Spring Boot backend (newsfeedbackend/)
      │
      ├── Postgres (Neon, externe hosted DB)
      ├── Anthropic Claude API  (AI samenvatting, selectie, podcast)
      ├── Tavily API            (websearch, alleen ad-hoc)
      ├── OpenAI TTS API        (podcast audio, optioneel)
      └── ElevenLabs TTS API    (podcast audio, optioneel)
```

**Backend:** Spring Boot 4.x, Kotlin 2.x, Maven, poort 8080. Spring Modulith voor module-isolatie.

**Frontend:** Flutter (Dart ^3.9), Riverpod state management, `just_audio` voor podcast.

**Database:** Postgres (Neon hosted); Flyway voor migraties. Audiobestanden op PVC.

## Index

- [`development.md`](development.md): lokaal bouwen, testen en ontwikkelconventies.
- [`functional-spec.md`](functional-spec.md): functionele afspraken en gebruikersgedrag.
- [`technical-spec.md`](technical-spec.md): technische keuzes, frameworks en codeconventies.
- [`deployment.md`](deployment.md): deploy-flow en machine-leesbare factory-config.
- [`secrets-local.md`](secrets-local.md): lokale secrets en waar die vandaan komen.
- [`agents/`](agents/): rol-specifieke instructies voor factory-agents.

## Primaire specificaties (buiten docs/factory/)

Uitgebreide specs staan in `specs/`:
- `specs/backend-functional-spec.md` — datamodellen, pipelines, externe systemen
- `specs/backend-technical-spec.md` — Spring Modulith modules, architectuur, tests
- `specs/frontend-spec.md` — alle schermen, navigatie, state management
- `specs/openapi.yaml` — REST API (source of truth voor API-contract)
