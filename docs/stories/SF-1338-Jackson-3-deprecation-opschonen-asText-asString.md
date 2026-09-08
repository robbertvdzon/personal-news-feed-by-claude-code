# SF-1338 - Jackson 3 deprecation opschonen: asText() → asString()

## Story

Jackson 3 deprecation opschonen: asText() → asString()

<!-- refined-by-factory -->

## Scope

`mvn clean compile` geeft momenteel 34 deprecation-warnings omdat Jackson 3 (`tools.jackson`) `JsonNode.asText()` / `asText(String)` heeft gedeprecate ten gunste van `asString()` / `asString(String)` — identieke signatuur en semantiek (`asText` is een `final` wrapper die naar `asString` delegeert), dus een pure mechanische rename zonder gedragswijziging.

Vervang alle `.asText(...)`-aanroepen op `JsonNode` door `.asString(...)` in de volgende 10 main-bestanden (goed voor exact 34 aanroepen, geverifieerd via grep):

- `ai/WhisperClient.kt` (1)
- `ai/infrastructure/OpenAiChatHttpClient.kt` (1)
- `events/domain/EventDateEnricher.kt` (2)
- `events/domain/EventExtractor.kt` (8)
- `events/domain/EventVideoDiscoveryPipeline.kt` (3)
- `podcast_source/domain/PodcastEpisodeSummarizer.kt` (5)
- `rss/domain/FeedItemGenerator.kt` (3)
- `rss/domain/FeedSelector.kt` (2)
- `rss/domain/RssSummarizer.kt` (3)
- `search/TavilyClient.kt` (6)

Buiten scope: `.asText()`-aanroepen in test-bestanden (`src/test/kotlin/.../e2e/*.kt`) — deze produceren geen compile-deprecation-warnings en vallen niet onder "mvn clean compile" acceptatiecriterium. Geen wijzigingen aan imports, publieke signatures, DTO's of ander gedrag.

## Acceptance criteria

- `mvn clean compile` geeft geen `asText`-deprecation-warnings meer.
- `mvn test` blijft groen (37 tests, ongewijzigd).
- Geen enkele wijziging aan publiek gedrag of API-contract; alleen mechanische `asText` → `asString` rename op `JsonNode`-aanroepen in de 10 genoemde main-bestanden.
- Test-bestanden (`e2e/*.kt`) blijven ongewijzigd.

## Aannames

- De storytekst noemt "13 bestanden", maar de daadwerkelijke lijst bevat 10 bestanden — geverifieerd via grep dat deze 10 bestanden samen exact de 34 gemelde `asText`-aanroepen in main-code bevatten. Het bestandsaantal "13" wordt als tikfout beschouwd; de expliciete bestandslijst is leidend en compleet.
- `asString()`/`asString(String)` bestaan met identieke signatuur in `tools.jackson.databind.JsonNode` (geverifieerd door decompilatie van `jackson-databind-3.0.0-rc4.jar`: `asText()` is `final` en delegeert, `asString()`/`asString(String)` zijn de vervangers), dus de rename is functioneel gelijkwaardig.

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summarized"}
