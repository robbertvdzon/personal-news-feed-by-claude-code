# Technical Spec

## Stack

- **Frontend**: Flutter 3.35 (Dart), state-management via Riverpod
  (`AsyncNotifierProvider`). Schermen zijn `ConsumerWidget`/`ConsumerStatefulWidget`.
- **Backend**: Spring Boot 4 / Kotlin 2, Maven, Spring Modulith. Maven-root:
  `newsfeedbackend/newsfeedbackend/`.
- **Database**: PostgreSQL (Neon) met Flyway-migraties.
- **API-contract**: `specs/openapi.yaml`.
- **Deployment**: OpenShift + ArgoCD. Preview-URL-template:
  `https://pnf-pr-{pr_num}.vdzonsoftware.nl`.

## Architectuurafspraken (frontend)

- Providers staan in `lib/providers/data_providers.dart`; per domein een
  `AsyncNotifierProvider` met `build()` (fetch + cache) en `save()` (optimistische update
  of validatie-veilig schrijven). Voorbeelden: `rssFeedsProvider`, `podcastFeedsProvider`.
- Schermen consumeren providers met `ref.watch(...)` en renderen via
  `asyncValue.when(data/loading/error)`. Error-tekst conventie: `Text('Fout: $e')`.
- Navigatie tussen schermen via `Navigator.push(MaterialPageRoute(...))` (geen named
  routes/deeplinks).
- Bij uitloggen worden de relevante providers ge-invalideerd in `settings_screen.dart`.

## Codeconventies

- Backend KDoc-blok (`/** ... */`) staat vóór de Spring-stereotype-annotatie
  (`@Service`/`@Component`), niet ertussen.
- Bij `delete()`-methoden in providers: bewaar de vorige state en herstel die in de
  `catch` bij API-fout (optimistic-delete rollback).
- `podcastFeedsProvider.save()` muteert de state pas ná een geslaagde PUT, zodat bij een
  HTTP 400 (ongeldige URL) de UI-staat correct blijft en de caller een snackbar toont.

## Bekende valkuilen

- De factory developer-runner mist flutter/dart én pip; Flutter- en pip-afhankelijke
  tests draaien alleen in CI.
- `PodcastFeed` heeft geen const-constructor — niet `const PodcastFeed(...)` gebruiken.
