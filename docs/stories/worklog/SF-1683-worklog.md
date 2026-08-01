# SF-1683 - Worklog

Story-context bij eerste pickup:
Podcast-feed-validatie achter publieke podcast_source-interface

Refactor module podcast_source (backend, Maven-root newsfeedbackend/newsfeedbackend), gedragsneutraal.

1. Voeg in de moduleroot com.vdzon.newsfeedbackend.podcast_source een publieke interface toe (bijv. PodcastFeedsService) met een methode savePodcastFeeds(username: String, settings: PodcastFeedsSettings): PodcastFeedsSettings. Volg het bestaande patroon van PodcastIngestionTrigger/PodcastEpisodeLookup: interface in de root, @Component-implementatie in podcast_source/domain/.

2. Verplaats de logica uit PodcastFeedsController.kt:46-59 naar die domain-implementatie, met exact dezelfde volgorde valideren -> opslaan -> triggeren: bestaande lijst via SettingsService.getPodcastFeeds(username), nieuwe URLs = feeds waarvan de url niet blank is en niet in de bestaande set staat, per nieuwe URL PodcastFeedFetcher.fetch(url, username), bij !ok afwijzen; daarna SettingsService.savePodcastFeeds(username, body) en PodcastIngestionTrigger.trigger(username); geef het opgeslagen resultaat terug.

3. Gooi bij een mislukte fetch com.vdzon.newsfeedbackend.common.BadRequestException met de LETTERLIJK ongewijzigde melding: "Kon feed niet ophalen: $url (${fetch.errorMessage ?: \"onbekende fout\"})". GlobalExceptionHandler levert daarvoor 400 + {"error": msg} - identiek aan nu.

4. Slank PodcastFeedsController af: verwijder de import van podcast_source.infrastructure.PodcastFeedFetcher en het veld fetcher; laat alleen SettingsService (voor GET /api/podcast-feeds) en de nieuwe publieke interface over (de PodcastIngestionTrigger-injectie vervalt als de service het triggeren overneemt). savePodcastFeeds wordt een dunne delegatie. Werk de KDoc van de controller bij zodat die niet langer de verhuisde validatielogica beschrijft.

5. Werk de verouderde KDoc in podcast_source/PodcastIngestionTrigger.kt regels 4-5 bij: noem SettingsController niet meer, maar de actuele caller (de nieuwe podcast-feeds-service in domain/).

6. Schrijf een unit-test op de nieuwe domain-service in de stijl van SettingsServiceImplSavePodcastFeedsTest.kt (Mockito + JUnit5): nieuwe URL wordt gefetcht; bestaande en blanco URLs niet; mislukte fetch -> BadRequestException met de exacte melding (incl. de 'onbekende fout'-fallback); happy path -> savePodcastFeeds + trigger aangeroepen in die volgorde.

Niet doen: PodcastFeedFetcher wijzigen, PodcastIngestE2eTest.kt wijzigen, specs/openapi.yaml wijzigen, ModuleStructureTest uitbreiden met een laagcheck, docs/kwaliteitsanalyse-backend.md bijwerken, frontend aanraken.

Verifieer voor oplevering: `cd newsfeedbackend/newsfeedbackend && mvn test` groen (incl. ModuleStructureTest) en `mvn verify` groen (e2e, Docker vereist); grep over alle @RestController-klassen levert nul treffers op een domain-/infrastructure-import; `git diff --stat` toont geen wijziging in specs/openapi.yaml en PodcastIngestE2eTest.kt. Doe daarna zelf een review-ronde op de diff. Werk docs/stories/worklog/SF-1683-worklog.md bij met wat je gedaan hebt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-1684 (development) uitgevoerd: podcast-feed-validatie verhuisd van de api-laag naar
  de domain-laag, achter een publieke interface in de moduleroot.

Wijzigingen (developer-run SF-1684):
- Nieuw `podcast_source/PodcastFeedsService.kt`: publieke interface in de moduleroot met
  `savePodcastFeeds(username, settings): PodcastFeedsSettings`, naast het bestaande
  patroon van `PodcastIngestionTrigger` / `PodcastEpisodeLookup`.
- Nieuw `podcast_source/domain/PodcastFeedsServiceImpl.kt` (`@Component`): bevat de uit de
  controller verhuisde logica in dezelfde volgorde valideren -> opslaan -> triggeren.
  Nieuwe URLs = niet-blanco en niet in de opgeslagen lijst; per nieuwe URL synchroon
  `PodcastFeedFetcher.fetch(url, username)`; bij `!ok` een
  `common.BadRequestException("Kon feed niet ophalen: $url (${errorMessage ?: "onbekende fout"})")`.
  `GlobalExceptionHandler.handleBadRequest` geeft daarvoor exact dezelfde 400 +
  `{"error": …}`-body als de vorige `ResponseStatusException`.
- `podcast_source/api/PodcastFeedsController.kt` afgeslankt: injecteert nog alleen
  `SettingsService` (voor de GET) en `PodcastFeedsService`; imports van
  `podcast_source.infrastructure.PodcastFeedFetcher` en `PodcastIngestionTrigger` en de
  bijbehorende velden zijn weg. `savePodcastFeeds` is een dunne delegatie; KDoc bijgewerkt.
- `podcast_source/PodcastIngestionTrigger.kt`: verouderde KDoc-verwijzing naar
  `SettingsController` vervangen door de podcast-feeds-service in `domain/`.
- Nieuw `src/test/kotlin/.../podcast_source/domain/PodcastFeedsServiceImplTest.kt`
  (Mockito + JUnit5, stijl van `SettingsServiceImplSavePodcastFeedsTest`): alleen nieuwe
  URLs worden gefetcht (bestaande/blanco niet), opslaan gebeurt vóór triggeren (`inOrder`),
  mislukte fetch geeft de exacte Nederlandse melding incl. de `onbekende fout`-fallback en
  slaat niet op / triggert niet.

Verificatie:
- `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend`: BUILD SUCCESS, unit-tests
  (incl. `ModuleStructureTest`) en 65 e2e-tests groen, 0 failures / 0 errors.
- Grep over alle `@RestController`-klassen: nul imports uit `…domain.` of
  `…infrastructure.` (exit 1 = geen treffers).
- `git diff --stat` toont geen wijziging in `specs/openapi.yaml` en `PodcastIngestE2eTest.kt`.
