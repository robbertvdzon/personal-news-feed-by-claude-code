# SF-2094 — 404-contract gelijktrekken met de controllers en translate-flow corrigeren

Subtaak: SF-2095 (development).

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md`, `technical-spec.md`, `specs/openapi.yaml` en de betrokken code gelezen
- [x] Zes ontbrekende `'404'`-responses toegevoegd in `specs/openapi.yaml`
- [x] Privacy-formulering (spiegel van `cancelRequest`) op de drie resource-endpoints
- [x] `DELETE /api/requests/{id}`-description: vast verzoek (`hourly-update-` / `daily-summary-`) geeft ook 404
- [x] `PodcastTranslationServiceImpl.startTranslation`: onbekende `episodeGuid` → `NotFoundException` (404)
- [x] `PodcastTranslationServiceImplTest`: not-found-test omgezet naar `NotFoundException`, inclusief testnaam
- [x] `specs/backend-functional-spec.md`: translate-trigger splitst 404 (aflevering onbekend) van 409 (status/transcript)
- [x] Verificatiegreps uit AC 7 uitgevoerd
- [x] `mvn -B --no-transfer-progress clean verify` groen

## Wat is gedaan en waarom

**1. `specs/openapi.yaml` — zes `'404'`-blokken toegevoegd** (kale stijl: alleen `description`,
geen gedeelde `components/responses`, conform de rest van het bestand):

| Operatie | 404-bron in code |
|---|---|
| `deleteRequest` (`DELETE /api/requests/{id}`) | `RequestController.kt:33` (+ beleidsweigering `RequestServiceImpl.kt:63`) |
| `rerunRequest` (`POST /api/requests/{id}/rerun`) | `RequestServiceImpl.kt:80` |
| `deletePodcast` (`DELETE /api/podcasts/{id}`) | `PodcastController.kt:40` |
| `resetUserPassword` | `AuthServiceImpl.kt:77` |
| `setUserRole` | `AdminServiceImpl.kt:38` / `AuthServiceImpl.kt:85` |
| `deleteUser` | `AdminServiceImpl.kt:52` |

De drie resource-endpoints noemen expliciet "onbekend id, of de resource is van een andere
gebruiker" — dezelfde formulering als het al correcte `cancelRequest`. Dat legt de bewuste
privacykeuze (404 in plaats van 403) vast in het contract in plaats van alleen in een
code-comment. De drie admin-endpoints krijgen de kortere "Gebruiker niet gevonden"; daar
speelt die motivatie niet. De bestaande `'403': Geen ROLE_ADMIN`-responses zijn ongemoeid
gelaten.

**2. Translate-flow rechtgezet.** `PodcastTranslationServiceImpl.startTranslation` gooide bij
een onbekende `episodeGuid` een `ConflictException` (409), terwijl het contract op die operatie
al een `'404': Aflevering niet gevonden` documenteerde. Gekozen voor de kant van het contract:
het is nu een `NotFoundException`, net als het buur-endpoint `lookupByRssItem`
(`PodcastTranslationController.kt:43`). De twee state-conflicten (status ≠ DONE, leeg transcript)
blijven `ConflictException`/409; het contract op die operatie is ongewijzigd en klopt nu.

`PodcastTranslationServiceImplTest` legde de oude 409 expliciet vast — die test heet nu
`startTranslation throws NotFoundException when episode is not found` en verwacht
`NotFoundException`. De twee tests eronder zijn ongewijzigd.

**3. `specs/backend-functional-spec.md`** bundelde in de Trigger-sectie van de translate-flow
"aflevering bestaat" en de status-precondities onder één "Bij conflict → HTTP 409". Die zin is
gesplitst in 404 (aflevering onbekend voor deze user) en 409 (status/transcript nog niet klaar).

## Verificatie

- `grep -rn "NotFoundException(" src/main` → 13 treffers (12 throw-sites + de klassedeclaratie
  in `common/Exceptions.kt:12`); elke vanaf een endpoint bereikbare throw-site heeft nu een
  `'404'` op de bijbehorende operatie.
- `grep -n "'404'" specs/openapi.yaml` → 12 treffers (6 bestaand, 6 nieuw). Alle twaalf hebben
  een 404-bron in de code: elf een `NotFoundException`, plus de expliciete
  `ResponseEntity.notFound()` op `RssController.kt:91` (`openapi.yaml:501`). Geen ongedekte 404.
- `specs/openapi.yaml` opnieuw geparseerd met SnakeYAML; de zes operaties leveren de verwachte
  response-keys op.
- `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`:
  **BUILD SUCCESS**, exitcode 0, 116 unit-tests + 66 e2e-tests, 0 failures, 0 errors (~3:42 min).

## Buiten scope gelaten

- De dode constructie rond rerun (`RequestController.kt:39` is onbereikbaar omdat
  `RequestServiceImpl.kt:80` zelf al gooit) — eigen story.
- Een generatiestap of contract-test voor `openapi.yaml`.
- Frontend: `rss_podcast_detail_screen.dart` matcht alleen op 409 en valt verder terug op de
  generieke melding; de niet-gevonden-tak is via de UI onbereikbaar (de translate-knop verschijnt
  pas na een geslaagde lookup). `specs/frontend-spec.md:175` beschrijft alleen het 409-pad en
  blijft correct.
