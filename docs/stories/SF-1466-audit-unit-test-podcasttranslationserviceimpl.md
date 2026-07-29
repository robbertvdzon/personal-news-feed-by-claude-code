# SF-1466 - [Audit] Unit-test toevoegen voor guard-clause-logica van PodcastTranslationServiceImpl

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en `docs/factory/technical-spec.md` gelezen.
- [x] `PodcastTranslationServiceImpl` en zijn dependencies (`PodcastRepository`,
      `PodcastEpisodeLookup`, `PodcastTranslator`, `Podcast`, `PodcastEpisode`,
      `EpisodeLookup`, `TranslationStart`) doorgenomen.
- [x] Referentiepatroon `SettingsServiceImplSavePodcastFeedsTest.kt` gevolgd
      (`@ExtendWith(MockitoExtension::class)`, `Mockito.mock(...)` in `@BeforeEach`).
- [x] `PodcastTranslationServiceImplTest.kt` toegevoegd met dekking voor
      `startTranslation` (6 scenario's) en `lookup` (3 scenario's).
- [x] `mvn test` gedraaid: BUILD SUCCESS, 80 tests groen (was 71), 0 failures/errors.

## Gedaan

Nieuw bestand:
`newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/podcast/domain/PodcastTranslationServiceImplTest.kt`
(9 tests):

- `startTranslation`:
  1. episode niet gevonden → `ConflictException`.
  2. episode-status != DONE → `ConflictException`.
  3. episode DONE maar blank transcript → `ConflictException`.
  4. bestaande vertaling met status != FAILED → idempotent (`created=false`),
     geen `upsert`/`translate`-aanroep (geverifieerd via
     `verifyNoMoreInteractions(podcastRepo)` / `verifyNoInteractions(translator)`).
  5. bestaande vertaling met status FAILED → nieuwe job (`created=true`),
     `podcastNumber = max+1`, `translator.translate` aangeroepen.
  6. happy path zonder bestaande vertaling → nieuwe `Podcast` correct gevuld
     (title, alle `translatedFrom*`-velden, status=PENDING, ttsProvider=OPENAI,
     podcastNumber=1) en `translator.translate` aangeroepen.
- `lookup`:
  1. episode niet gevonden → `null`.
  2. zonder bestaande vertaling → `EpisodeLookup` met `translatedPodcastId=null`
     (en overige `translatedPodcast*`-velden null).
  3. met bestaande vertaling → alle `translatedPodcast*`-velden correct gevuld.

Mocking-detail: `PodcastTranslator.translate(username, podcastId, episodeGuid)`
genereert de `podcastId` intern via `UUID.randomUUID()`, dus die kan niet
vooraf met een exacte waarde gestubd/geverifieerd worden. Om de bekende
`any()`-op-Kotlin-non-null-parameter-NPE te vermijden (zie agent-tip
`pnf-mockito-first-use-sf1345`) is het `upsert`-argument gevangen via een
`doAnswer`-lambda i.p.v. matchers, en is daarna met de zo verkregen concrete
waarde geverifieerd (`verify(translator).translate("alice", saved.id, ep.guid)`).

`mvn verify` (Testcontainers e2e) kon niet lokaal gedraaid worden — Docker was
niet beschikbaar in deze sandbox (`docker info` faalt). Buiten scope van deze
subtaak (geen e2e/ffmpeg-wijzigingen).

## Niet gedaan / aangepast

- Geen wijzigingen aan productiecode: `PodcastTranslationServiceImpl` gedraagt
  zich exact zoals in de story beschreven, er is geen bug blootgelegd.
- De ffmpeg-afhankelijke e2e-vertaalflow (`PodcastTranslator.translate`) blijft
  buiten scope, zoals gevraagd.
