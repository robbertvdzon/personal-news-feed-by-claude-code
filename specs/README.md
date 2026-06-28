# Personal News Feed — Specificaties

Dit is het instapdocument voor de specificaties van de **Personal News Feed** app. Geef dit bestand als eerste aan een AI-model, samen met de drie spec-bestanden hieronder.

---

## Wat is deze app?

Personal News Feed is een zelf-gehoste, persoonlijke nieuwslezer met AI-curation. De app:

- Haalt RSS-feeds op en laat AI per artikel een Nederlandstalige samenvatting maken en een categorie toewijzen
- Selecteert automatisch de meest relevante artikelen voor een persoonlijke feed op basis van gebruikersgedrag (likes, sterren, leesgedrag)
- Verwerkt ad-hoc zoekopdrachten: geef een onderwerp op en de AI zoekt en vat actuele artikelen samen
- Genereert dagelijks een AI-nieuwsoverzicht
- Genereert podcasts (script + audio) op basis van recente nieuwsartikelen, in een interview-format met twee stemmen
- Ondersteunt meerdere gebruikers, elk met volledig eigen data en instellingen

---

## Repostructuur

```
personal-news-feed/
├── specs/                        ← deze map (specificaties)
│   ├── README.md                 ← dit bestand
│   ├── backend-functional-spec.md ← backend gedrag (black-box)
│   ├── backend-technical-spec.md  ← backend architectuur & conventies
│   ├── frontend-spec.md          ← frontend schermen & functionaliteit
│   ├── openapi.yaml              ← volledige REST API definitie (OpenAPI 3.1)
│   ├── e2e.md                    ← pointer naar de end-to-end-test scenario's
│   └── branch-commit-convention.md ← branch- en commit-naamconventies
├── newsfeedbackend/              ← Spring Boot backend (Kotlin/Maven)
│   └── newsfeedbackend/
│       └── src/...
├── frontend/                     ← Flutter frontend (Dart) — volledige app
│   └── lib/...
└── frontend-reader/              ← Flutter read-only reader-variant
    └── lib/...
```

---

## Specificatiebestanden

| Bestand | Inhoud |
|---------|--------|
| [`backend-functional-spec.md`](./backend-functional-spec.md) | Wat de backend doet: datamodellen, achtergrondprocessen (pipelines), externe systemen, configuratie, foutafhandeling |
| [`backend-technical-spec.md`](./backend-technical-spec.md) | Hoe de backend gebouwd is: Spring Modulith modules, gelaagde architectuur, DTOs, logging, Grafana-monitoring, Cucumber integratie-tests, IntelliJ setup |
| [`frontend-spec.md`](./frontend-spec.md) | Alle schermen, navigatie, gebruikersacties, state management, WebSocket-integratie, audio-afspelen |
| [`openapi.yaml`](./openapi.yaml) | Alle REST-endpoints met paden, methoden, parameters, request/response-bodies en dataschema's. Dit is de **source of truth** voor de API-interface tussen backend en frontend. |
| [`e2e.md`](./e2e.md) | Pointer naar `/e2e/readme.md` met de end-to-end-test scenario's, runner-procedure en testrun-administratie. |
| [`branch-commit-convention.md`](./branch-commit-convention.md) | Branch-naamconventies (`ai/`, `feat/`, …) en commit-/PR-message-format, inclusief de op `ai/<id>`-branches afgedwongen story-id-prefix. |

---

## Architectuur in één oogopslag

```
Flutter app (frontend/)
      │
      │  REST (JWT Bearer)      WebSocket
      ▼                              ▼
Spring Boot backend (newsfeedbackend/)
      │
      ├── PostgreSQL (Neon)     (alle data; Flyway-migraties; podcast-audio als BYTEA)
      ├── OpenAI API            (AI samenvatting, selectie, podcast, events, transcriptie/TTS)
      ├── Tavily API            (websearch + extractie: ad-hoc + events-discovery)
      └── ElevenLabs TTS API    (podcast audio, optioneel)
```

**Backend:** Spring Boot 4.x, Kotlin 2.x, Maven, poort **8080**. Persistentie via **PostgreSQL** (Neon, cloud) met **Flyway**-migraties; podcast-audio staat als BYTEA in de database.

**Frontend:** Flutter (Dart SDK ^3.9), Riverpod voor state management, `just_audio` voor podcast-afspelen.

### Veelgebruikte commando's (vanuit `frontend/`)

| Commando | Wat het doet |
|----------|-------------|
| `make serve-ext` / `make run` | Start web-app op poort **3000**, backend op `http://localhost:8080` |
| `make build-apk-ext` | Bouwt release-APK met backend `http://217.120.100.76:19283` |
