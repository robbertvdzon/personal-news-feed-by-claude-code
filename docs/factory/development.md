# Development

Lokale ontwikkelflow voor de Personal News Feed-repo (PNF).

## Repo-structuur

- `frontend/` — Flutter-app (Dart). Schermen in `lib/screens/`, Riverpod-providers
  in `lib/providers/`, models in `lib/models/`, API-clients in `lib/api/`, widgets in
  `lib/widgets/`. Widgettests in `frontend/test/`.
- `newsfeedbackend/newsfeedbackend/` — Spring Boot 4 / Kotlin 2 backend (Maven, Spring
  Modulith). PostgreSQL (Neon) met Flyway-migraties.
- `specs/openapi.yaml` — OpenAPI-spec; backend en frontend volgen dit contract.
- `docs/` — factory-docs en story-worklogs.

## Commands

- Frontend build: `cd frontend && flutter build web`
- Frontend lint/analyze: `cd frontend && flutter analyze`
- Frontend tests: `cd frontend && flutter test`
- Backend build: `cd newsfeedbackend/newsfeedbackend && mvn -q compile`
- Backend unit tests: `cd newsfeedbackend/newsfeedbackend && mvn -q test`

## Runner-beperkingen

- De factory developer-runner heeft **geen** flutter/dart-binary; `flutter analyze`/
  `flutter test` draaien alleen in CI of in de `.devcontainer` (Flutter 3.35). Bij pure
  frontend-stories: tests wel schrijven, maar documenteren dat analyze/test door CI worden
  gevalideerd.
- `mvn` is wél aanwezig in de developer-runner; backend-build/tests draaien lokaal mét
  netwerk (offline faalt door ontbrekende cache).

## Conventions

- Schermen die te lang worden, splits je op door editors 1-op-1 naar een nieuw
  `lib/screens/<naam>_screen.dart` (ConsumerWidget met eigen Scaffold + AppBar) te
  verhuizen en de inline-sectie te vervangen door een navigatie-`ListTile`
  (`Icon` + titel + `Icon(Icons.chevron_right)` + `MaterialPageRoute`), consistent met
  de bestaande API-log/Beheer-tiles.
- Widgettests voor schermen met `AsyncNotifierProvider` gebruiken een fake-notifier die
  de echte notifier extend't en `build()`/`save()` overschrijft, geïnjecteerd via
  `ProviderScope(overrides: [...])`. Let op: `PodcastFeed` heeft geen const-constructor.
