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

Review (SF-1684, reviewer-run):
- Volledige story-diff `git diff main...HEAD` beoordeeld (6 bestanden, 265+/37-). Akkoord.
- AC 1-9 nagelopen: controller heeft geen `domain`/`infrastructure`-import meer (grep over
  alle 14 `@RestController`-klassen: nul treffers), businesslogica staat 1-op-1 in
  `podcast_source/domain/PodcastFeedsServiceImpl.kt` achter de moduleroot-interface
  `PodcastFeedsService`, volgorde valideren -> opslaan -> triggeren ongewijzigd.
- Gedragsneutraliteit responsvorm bevestigd: `ResponseStatusException(BAD_REQUEST, msg)`
  ging via `handleResponseStatus` naar 400 + `{"error": reason}`; `BadRequestException`
  gaat via `handleBadRequest` naar 400 + `{"error": message}` — identiek. Meldingstekst
  letterlijk overgenomen incl. de `onbekende fout`-fallback.
- Gerichte eigen check: `mvn -B -o test -Dtest=PodcastFeedsServiceImplTest,ModuleStructureTest`
  -> 5 tests, 0 failures / 0 errors (BUILD SUCCESS); geen nieuwe Modulith-schendingen.
- Niet-blokkerende opmerkingen: (a) `@MockitoSettings(strictness = LENIENT)` in de nieuwe
  unit-test lijkt overbodig (alle stubs worden gebruikt) en wijkt af van de referentietest;
  (b) `podcast_source/domain/PodcastIngestionPipeline.kt:63` noemt nog `SettingsController`
  in KDoc — buiten scope van deze story (AC #6 betreft alleen `PodcastIngestionTrigger.kt`).

Test (SF-1685, tester-run):
- Vangnet `.factory/verification.yaml` (`mvn -B --no-transfer-progress clean verify` in
  `newsfeedbackend/newsfeedbackend`): exitcode 0, BUILD SUCCESS — unit 84/84 (incl.
  `ModuleStructureTest` en de nieuwe `PodcastFeedsServiceImplTest` 4/4), e2e 65/65
  (incl. `PodcastIngestE2eTest` 4/4). 0 failures, 0 errors.
- Statische AC-checks: grep over alle 14 `@RestController`-klassen -> nul
  `domain`/`infrastructure`-imports (AC1); `PodcastIngestionTrigger.kt` noemt
  `SettingsController` niet meer (AC6); `specs/openapi.yaml` en `PodcastIngestE2eTest.kt`
  0 diff t.o.v. main (AC7/AC9). Reviewer-commit `cbcad21` is worklog-only, dus de preview
  (build `386f1fa`) draait alle codewijzigingen van de story.
- Preview pnf-pr-197, API-gedrag `PUT /api/podcast-feeds` (wegwerp-user `tester_sf-1683`,
  na afloop verwijderd via `DELETE /api/account/me` -> 200, login daarna 401):
  onbereikbare feed -> 400 `{"error":"Kon feed niet ophalen: <url> (<reden>)"}` en niets
  opgeslagen; geldige feed -> 200 + persistent via GET; bestaande URL opnieuw meesturen ->
  200 zonder nieuwe fetch (backend-logs tonen geen `PodcastFeedFetcher`-regel voor die URL);
  bestaande + nieuwe kapotte URL -> 400 terwijl de bestaande lijst ongewijzigd blijft;
  bestaande + nieuwe geldige URL -> 200 en alleen de nieuwe wordt gefetcht.
- Volgorde valideren -> opslaan -> triggeren bevestigd in de backend-logs: na elke
  geslaagde PUT volgt `[PodcastIngest] start voor 'tester_sf-1683'`, na een 400 niet.
  De 400 loopt nu zichtbaar via `GlobalExceptionHandler` (`400 Bad Request: Kon feed niet
  ophalen: ...`), wat de overstap naar `BadRequestException` bevestigt.
- Browser (Playwright, 420x900, screenshots in /work/screenshots): via Instellingen ->
  RSS feeds een onbereikbare podcast-URL toegevoegd -> rode snackbar met exact
  "Kon feed niet ophalen: ... (geblokkeerd: ... kan niet worden geresolved)", bestaande
  feeds blijven staan (06-foutmelding.png).
- Opmerking (pre-existing, niet door deze story veroorzaakt): een blanco podcast-URL wordt
  door de fetch-lus overgeslagen maar daarna alsnog door `SettingsService` afgewezen met
  400 `{"error":"Ongeldige podcast-feed-URL '  ': ongeldige URL"}` — identiek aan main.
- Tijdens de run herstartte de backend-pod (nieuwe rollout), waardoor bestaande JWT's door
  het ephemeral secret ongeldig werden (403). Opnieuw ingelogd; geen productbug.
