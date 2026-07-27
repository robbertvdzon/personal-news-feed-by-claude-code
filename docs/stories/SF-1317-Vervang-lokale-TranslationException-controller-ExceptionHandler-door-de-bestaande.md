# SF-1317 - Vervang lokale TranslationException + controller-@ExceptionHandler door de bestaande centrale ConflictException

## Story

Vervang lokale TranslationException + controller-@ExceptionHandler door de bestaande centrale ConflictException

<!-- refined-by-factory -->

## Scope
In `podcast/domain/PodcastTranslationServiceImpl.kt` de lokale `TranslationException`-klasse vervangen door de al bestaande centrale `ConflictException` uit `common/Exceptions.kt`:

- De 3 `throw TranslationException(...)`-aanroepen (regels 57, 60, 65) worden `throw ConflictException(...)`, met behoud van exact dezelfde NL-foutmeldingen.
- Import toevoegen: `com.vdzon.newsfeedbackend.common.ConflictException`.
- De `TranslationException`-klasse (regel 114) wordt verwijderd.
- In `PodcastTranslationController.kt` wordt de lokale `@ExceptionHandler(TranslationException::class)`-functie `handleTranslationException` (regels 60-63) verwijderd, samen met de nu ongebruikte imports (`TranslationException`, `HttpStatus`, `ExceptionHandler`, en `ResponseEntity` indien niet meer elders in het bestand gebruikt — controleren of `translate()` nog `ResponseEntity` nodig heeft).
- `ConflictException` is al `@ResponseStatus(HttpStatus.CONFLICT)` en wordt al centraal afgehandeld door `GlobalExceptionHandler.handleConflict`, die exact dezelfde responsvorm teruggeeft (`409` + `{"error": "<message>"}`). HTTP-gedrag blijft dus identiek.

## Acceptance criteria
1. `TranslationException` bestaat niet meer in de codebase (klasse verwijderd uit `PodcastTranslationServiceImpl.kt`).
2. De 3 validatiefouten in `startTranslation` (geen aflevering gevonden, episode nog niet DONE, leeg transcript) gooien `ConflictException` met dezelfde NL-berichttekst als voorheen.
3. `PodcastTranslationController.kt` bevat geen lokale `@ExceptionHandler` meer; alle 409-afhandeling loopt via de centrale `GlobalExceptionHandler` in `common/Exceptions.kt`.
4. Response bij een conflict blijft HTTP 409 met JSON-body `{"error": "<NL-boodschap>"}`, functioneel ononderscheidbaar van het huidige gedrag (geverifieerd: `GlobalExceptionHandler.handleConflict` retourneert exact dezelfde vorm).
5. Ongebruikte imports (`TranslationException`, en eventueel `ExceptionHandler`/`HttpStatus` als die nergens anders meer in het controller-bestand gebruikt worden) zijn verwijderd.
6. Er zijn geen bestaande tests die op `TranslationException` testen (geverifieerd via grep: geen treffers in `src/test`), dus er hoeft niets "mee te verhuizen" — mocht de developer alsnog een test tegenkomen die er impliciet van uitgaat, dan verhuist die naar de `ConflictException`-verwachting.
7. `mvn test` blijft groen.

## Aannames
- Er bestaan geen tests die specifiek op de klasse `TranslationException` of op de lokale controller-exceptionhandler testen (bevestigd via repo-brede grep, 0 treffers in test-bronnen).
- `ResponseEntity` als returntype van `translate()` in de controller blijft nodig en die import blijft dus staan; alleen de importregels die uitsluitend voor de verwijderde handler nodig waren (`TranslationException`, en mogelijk `ExceptionHandler`/`HttpStatus` als nergens anders gebruikt) worden opgeruimd.
- Geen wijziging aan `specs/openapi.yaml` nodig: het API-contract (409 + foutbericht) verandert niet.

## Eindsamenvatting

## Eindsamenvatting — SF-1317: TranslationException → ConflictException

**Wat is gebouwd**
De lokale `TranslationException` in `PodcastTranslationServiceImpl.kt` is vervangen door de centrale `ConflictException` uit `common/Exceptions.kt`:
- De 3 validatiefouten in `startTranslation` (geen aflevering gevonden, episode nog niet DONE, leeg transcript) gooien nu `ConflictException`, met exact behoud van de bestaande NL-foutmeldingen.
- De lokale `class TranslationException` (incl. KDoc) is verwijderd.
- In `PodcastTranslationController.kt` is de lokale `@ExceptionHandler(TranslationException::class)`-functie `handleTranslationException` (incl. KDoc) verwijderd, samen met de nu ongebruikte imports (`TranslationException`, `ExceptionHandler`).

**Keuzes**
- `HttpStatus` en `ResponseEntity` bleven in de controller staan, want `translate()` gebruikt die nog.
- 409-afhandeling bij conflicten loopt nu volledig via de bestaande centrale `GlobalExceptionHandler.handleConflict`, die dezelfde responsvorm teruggeeft (`409` + `{"error": "<message>"}`) — functioneel identiek gedrag voor de frontend.

**Getest**
- Grep bevestigt: geen enkele referentie naar `TranslationException` meer in de codebase.
- `mvn test`: BUILD SUCCESS, 37 tests, 0 failures/errors (incl. `ModuleStructureTest`).

**Bewust niet gedaan**
- Geen wijziging aan `specs/openapi.yaml`, aangezien het API-contract (409 + foutbericht) ongewijzigd blijft.
- Geen aanpassing van tests, omdat er geen bestaande tests specifiek op `TranslationException` of de lokale exceptionhandler testten.
