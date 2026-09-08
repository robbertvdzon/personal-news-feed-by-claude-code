# SF-1466 - [Audit] Unit-test toevoegen voor guard-clause-logica van PodcastTranslationServiceImpl

## Story

[Audit] Unit-test toevoegen voor guard-clause-logica van PodcastTranslationServiceImpl

Voeg PodcastTranslationServiceImplTest.kt toe (newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/podcast/domain/) die PodcastTranslationServiceImpl unit-test met gemockte dependencies (PodcastRepository, PodcastEpisodeLookup, PodcastTranslator via Mockito.mock(...), zelfde patroon als SettingsServiceImplSavePodcastFeedsTest.kt - geen e2e-harnas, geen ffmpeg/Whisper nodig). Dek in startTranslation(username, episodeGuid): (1) episode niet gevonden -> ConflictException; (2) episode-status niet DONE -> ConflictException; (3) episode DONE maar leeg transcript -> ConflictException; (4) bestaande vertaling met status != FAILED -> idempotent return (created=false, geen nieuwe upsert/translate-call); (5) bestaande vertaling met status FAILED -> nieuwe job (created=true, translator.translate aangeroepen); (6) happy path zonder bestaande vertaling -> nieuwe Podcast correct gevuld, translator.translate aangeroepen. Dek ook lookup(username, rssItemId): episode niet gevonden -> null; zonder bestaande vertaling -> EpisodeLookup met translatedPodcastId=null; met bestaande vertaling -> velden correct gevuld. Geen wijzigingen aan productiecode tenzij een bug wordt blootgelegd (dan expliciet melden). De ffmpeg-afhankelijke e2e-vertaalflow blijft buiten scope.

## Eindsamenvatting

## Eindsamenvatting SF-1466 — Unit-test voor guard-clause-logica van PodcastTranslationServiceImpl

**Wat is gebouwd**
Er is een nieuw testbestand toegevoegd: `PodcastTranslationServiceImplTest.kt` (in `newsfeedbackend/newsfeedbackend/src/test/kotlin/.../podcast/domain/`), met 9 unit tests die `PodcastTranslationServiceImpl` volledig dekken met gemockte dependencies (`PodcastRepository`, `PodcastEpisodeLookup`, `PodcastTranslator`).

Gedekte scenario's voor `startTranslation(username, episodeGuid)`:
1. Episode niet gevonden → `ConflictException`.
2. Episode-status ongelijk aan DONE → `ConflictException`.
3. Episode DONE maar leeg/blank transcript → `ConflictException`.
4. Bestaande vertaling met status ≠ FAILED → idempotent resultaat (`created=false`), geen nieuwe upsert/translate-aanroep.
5. Bestaande vertaling met status FAILED → nieuwe job (`created=true`), `translator.translate` wordt aangeroepen.
6. Happy path zonder bestaande vertaling → nieuwe `Podcast` correct gevuld (title, translatedFrom-velden, status=PENDING, ttsProvider=OPENAI, podcastNumber) en `translator.translate` aangeroepen.

En voor `lookup(username, rssItemId)`:
- Episode niet gevonden → `null`.
- Zonder bestaande vertaling → `EpisodeLookup` met `translatedPodcastId=null`.
- Met bestaande vertaling → alle velden correct gevuld.

**Gemaakte keuzes**
- Gevolgd is exact het bestaande testpatroon uit `SettingsServiceImplSavePodcastFeedsTest.kt` (JUnit5 + MockitoExtension, mocks in `@BeforeEach`).
- Omdat `PodcastTranslator.translate` intern een `UUID.randomUUID()` genereert, kon het `upsert`-argument niet vooraf exact gestubd worden; dit is opgelost door het argument via een `doAnswer`-lambda te vangen en pas daarna te verifiëren — dit voorkomt een bekend Mockito/Kotlin-NPE-probleem met `any()` op non-null parameters.

**Wat is getest**
- `mvn test`: BUILD SUCCESS, 80 tests groen (was 71 vóór deze story), 0 failures/errors.
- Door de tester nogmaals bevestigd via `mvn clean verify` (volledige suite inclusief Testcontainers e2e-tests): BUILD SUCCESS, 0 failures/errors. De 9 nieuwe tests zijn inhoudelijk geverifieerd tegen de service-implementatie — alle 6 startTranslation- en 3 lookup-scenario's kloppen exact.

**Bewust niet gedaan**
- Geen wijzigingen aan productiecode: `PodcastTranslationServiceImpl` gedraagt zich zoals verwacht, er is geen bug blootgelegd.
- De ffmpeg-afhankelijke e2e-vertaalflow (`PodcastTranslator.translate`) blijft buiten scope, zoals vooraf afgesproken.
- `mvn verify` kon door de developer zelf niet lokaal gedraaid worden (Docker niet beschikbaar in de sandbox); dit is later door de tester alsnog succesvol uitgevoerd.
