# SF-1755 — Dode code verwijderen (7 punten) in newsfeedbackend

Onderdeel van story SF-1753 ([Audit] Dode code opruimen: 8 symbolen zonder aanroepers verwijderen).
Puur opruimwerk: uitsluitend verwijderingen van symbolen zonder aanroepers plus de
daardoor losgekomen imports en KDoc. Geen functionele wijziging.

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en de betrokken bronbestanden lezen
- [x] Per symbool repo-breed greppen om te bevestigen dat er geen callers zijn
- [x] Punt 1: `ExternalCallLogger.timed()` + `TimedContext` (+ impl + 3 test-fakes)
- [x] Punt 2: `OpenAiChatClient.completeJson()` (+ impl, fake, dode `responseFormat`-parameter)
- [x] Punt 3: `PodcastEpisodeRepository.findOneReadyForTranscript()` + KDoc-herschrijving
- [x] Punt 4: `PodcastEpisodeRepository.countForFeed()`
- [x] Punt 5: `PodcastEpisodeRepository.deleteForFeed()`
- [x] Punt 6: `AuthService.userExists()` + override in `AuthServiceImpl`
- [x] Punt 7: ongebruikte import `java.util.UUID` in `Mp3Concatenator`
- [x] Testaantal vóór/na vergelijken (moet gelijk blijven)
- [x] `mvn -B clean verify` draaien (vangnet uit `.factory/verification.yaml`)
- [x] Zelfreview van de diff + worklog bijwerken

## Wat is gedaan en waarom

Alle zeven punten zijn verwijderd zoals gerefined. Per symbool is eerst repo-breed gegrept;
geen enkel verwijderd symbool had nog een caller (de enige treffers waren de definitie, de
override in de impl en — bij `timed()`/`completeJson()` — de lege overrides in test-fakes die
puur bestonden om de interface te vullen).

Afgeleide opruiming binnen de opdracht:

- `import java.time.Instant` uit `ExternalCallLogger.kt`; `java.time.Instant` en `java.util.UUID`
  uit `ExternalCallLoggerImpl.kt` (alleen door `timed()`/`recordAndAppend()` gebruikt).
- `doComplete`'s `responseFormat`-parameter, de `if (responseFormat != null)`-regel en de twee
  `responseFormat = null`-argumenten: met `completeJson()` weg gaven beide resterende callers
  al `null` door, dus gedragsneutraal. `mapper` blijft in gebruik (body-serialisatie + parsing).
- KDoc: klasse-doc van `OpenAiChatClient` noemt alleen nog `[complete]`; de
  `[completeJson]`-opsommingsregel in `OpenAiChatHttpClient` is weg en de opsomming zegt nu
  "twee overloads"; de KDoc van `findReadyForTranscript(now, limit)` is herschreven zodat die
  zelfstandig leesbaar is en niet meer naar het verwijderde `findOneReadyForTranscript` verwijst.

Bewust niet gedaan (conform de refined story): `EventDenylistRepository.ids` (module bestaat niet
meer sinds SF-1746, no-op), de vermeende ongebruikte imports in `PodcastTranslationServiceImplTest`
en `PodcastFeedsServiceImplTest` (die zijn wél in gebruik), geen vervangende helper voor de
handgebouwde ExternalCall-logblokken, en niets aan `CategoryResult`, `TopicHistory`-counters of
`application.properties`.

## Verificatie

- Testaantal ongewijzigd: 147 `@Test`/`@ParameterizedTest`-annotaties vóór én na de wijziging
  (gemeten met `git stash` op dezelfde working tree). Er is geen test verwijderd of toegevoegd —
  alleen de interface-vullende `timed()`-/`completeJson()`-overrides in de fakes.
- `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`
  (= `backend-maven-verify` uit `.factory/verification.yaml`): zie worklog voor het resultaat.
- Diff-zelfreview: uitsluitend verwijderingen en KDoc-aanpassingen; geen wijziging aan
  `specs/openapi.yaml`, `frontend/`, `frontend-reader/`, HTTP-endpoints of database-migraties.
