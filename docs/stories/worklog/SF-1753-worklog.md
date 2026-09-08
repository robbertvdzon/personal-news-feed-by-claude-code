# SF-1753 - Worklog

Story-context bij eerste pickup:
Dode code verwijderen (7 punten) in newsfeedbackend

Verwijder in newsfeedbackend/newsfeedbackend de zeven symbolen zonder aanroepers, gedragsneutraal:
1) ExternalCallLogger.timed() + class TimedContext; ExternalCallLoggerImpl.timed() + de alleen daardoor aangeroepen private recordAndAppend(); de override fun timed-blokken in de drie test-fakes (rss/RssFetcherSsrfTest, rss/RssFetcherImageUrlTest, podcast_source/PodcastFeedFetcherSsrfTest) - de fakes en hun log() blijven. Verwijder de daardoor ongebruikte imports (java.time.Instant in beide main-bestanden, java.util.UUID in de impl).
2) OpenAiChatClient.completeJson() + de override in ai/infrastructure/OpenAiChatHttpClient + de override in src/test/.../e2e/FakeOpenAiChatClient. Verwijder daarna de dan dode responseFormat-parameter van doComplete, de if (responseFormat != null)-regel en de twee 'responseFormat = null'-argumenten; mapper blijft. Werk de KDoc bij: klasse-doc OpenAiChatClient naar alleen [complete], en de [completeJson]-opsommingsregel in OpenAiChatHttpClient weg.
3) PodcastEpisodeRepository.findOneReadyForTranscript(now) incl. KDoc; herschrijf de KDoc van findReadyForTranscript(now, limit) zodat die zelfstandig leesbaar is en niet meer naar de verwijderde methode verwijst (de methode zelf blijft, wordt gebruikt door PodcastRecoveryScheduler).
4) PodcastEpisodeRepository.countForFeed(...).
5) PodcastEpisodeRepository.deleteForFeed(...).
6) AuthService.userExists(username) + de override in auth/domain/AuthServiceImpl.
7) Ongebruikte import java.util.UUID in podcast/infrastructure/Mp3Concatenator.

NIET doen: EventDenylistRepository.ids (module bestaat niet meer, no-op); de imports in PodcastTranslationServiceImplTest en PodcastFeedsServiceImplTest (die zijn wel in gebruik); geen vervangende helper voor de tien handgebouwde ExternalCall-logblokken; niets aan CategoryResult, TopicHistory-counters of application.properties.

Grep per symbool repo-breed voordat je verwijdert. Verwijder of voeg geen tests toe: het testaantal moet gelijk blijven aan voor de wijziging (tel vooraf). Verifieer met 'mvn clean compile' (0 warnings, let op nieuwe unused-import-warnings), 'mvn test' en 'mvn verify' vanuit newsfeedbackend/newsfeedbackend. Sluit af met een zelfreview van de diff: uitsluitend verwijderingen en KDoc-aanpassingen, geen wijziging aan publiek gedrag, HTTP-endpoints, specs/openapi.yaml, frontend/, frontend-reader/ of de database. Werk docs/stories/worklog/SF-1753-worklog.md bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-1755 (development) uitgevoerd: alle zeven punten verwijderd, uitsluitend deletes +
  KDoc-aanpassingen. Details in docs/stories/SF-1755-dode-code-verwijderen.md.
  - Punt 1: ExternalCallLogger.timed() + TimedContext, ExternalCallLoggerImpl.timed() +
    recordAndAppend(), de drie interface-vullende timed()-overrides in de test-fakes, en de
    daardoor ongebruikte imports (java.time.Instant in beide main-bestanden, java.util.UUID
    in de impl).
  - Punt 2: OpenAiChatClient.completeJson() + override in OpenAiChatHttpClient + override in
    FakeOpenAiChatClient; daarna de dood geworden responseFormat-parameter van doComplete, de
    if (responseFormat != null)-regel en de twee 'responseFormat = null'-argumenten (beide
    resterende callers gaven al null door → gedragsneutraal). mapper blijft in gebruik.
    KDoc bijgewerkt: klasse-doc noemt alleen nog [complete]; de [completeJson]-opsommingsregel
    is weg ("drie overloads" → "twee overloads").
  - Punt 3-5: PodcastEpisodeRepository.findOneReadyForTranscript() (incl. KAN-60-KDoc),
    countForFeed() en deleteForFeed(). De KDoc van findReadyForTranscript(now, limit) is
    herschreven zodat die zelfstandig leesbaar is en niet meer naar het verwijderde symbool
    verwijst; de methode zelf blijft (PodcastRecoveryScheduler gebruikt 'm).
  - Punt 6: AuthService.userExists() + de override in AuthServiceImpl.
  - Punt 7: ongebruikte import java.util.UUID in Mp3Concatenator.
- Vóór elke verwijdering repo-breed gegrept: geen enkel symbool had nog een caller.
- Niet gedaan (conform refined story): EventDenylistRepository.ids (module weg sinds SF-1746,
  no-op) en de imports in PodcastTranslationServiceImplTest/PodcastFeedsServiceImplTest (wél in
  gebruik). Ook geen vervangende helper voor de handgebouwde ExternalCall-logblokken en niets
  aan CategoryResult, TopicHistory-counters of application.properties.

Verificatie:
- Testaantal ongewijzigd: 147 @Test/@ParameterizedTest-annotaties vóór én na (gemeten via
  git stash op dezelfde tree). Geen test verwijderd of toegevoegd.
- mvn -B --no-transfer-progress clean verify (= backend-maven-verify uit
  .factory/verification.yaml) in newsfeedbackend/newsfeedbackend: BUILD SUCCESS, exitcode 0,
  94 unit-tests + 52 e2e-tests, 0 failures / 0 errors.
- Kotlin main-compile: 0 warnings (dus geen nieuwe unused-import-warnings). De WARNING-regels in
  het log komen allemaal uit de test-compile-fase en zijn pre-existing ('asText() is deprecated'
  in de e2e-tests), niet door deze wijziging veroorzaakt.
- Diff-zelfreview: 12 bestanden, 9 insertions / 192 deletions; de 9 toegevoegde regels zijn
  uitsluitend KDoc. Geen wijziging aan specs/openapi.yaml, frontend/, frontend-reader/,
  HTTP-endpoints of database-migraties.

Review (SF-1755, reviewer):
- Volledige story-diff t.o.v. main gereviewd (14 bestanden incl. 2 nieuwe docs). Alle zeven
  punten aanwezig en niets extra's; geen implementatie buiten scope.
- Restant-grep repo-breed op timed/TimedContext/completeJson/responseFormat/response_format/
  userExists/findOneReadyForTranscript/countForFeed/deleteForFeed: geen enkele treffer meer in
  code, specs/, docs/factory/, README.md of runbook.md — alleen historische story-logs
  (SF-113/SF-114/SF-1739), die terecht ongewijzigd blijven.
- Imports gecontroleerd: java.time.Instant en java.util.UUID in OpenAiChatHttpClient zijn nog
  wél in gebruik (logCall) en blijven terecht staan; Mp3Concatenator, ExternalCallLogger(.Impl)
  en de drie test-fakes houden geen ongebruikte imports over.
- Testaantal geverifieerd op beide revisies: 147 @Test/@ParameterizedTest op main én op
  ai/SF-1753 — geen test verwijderd of toegevoegd.
- KDoc: geen verwijzing meer naar een verwijderd symbool in OpenAiChatClient,
  OpenAiChatHttpClient of PodcastEpisodeRepository; findReadyForTranscript-KDoc is zelfstandig
  leesbaar.
- Akkoord: geen blockers, geen bugs.

Test (SF-1756, tester):
- Statische verificatie: repo-brede grep op timed/TimedContext/completeJson/responseFormat/
  userExists/findOneReadyForTranscript/countForFeed/deleteForFeed levert buiten .task.md en
  docs/stories/ geen enkele treffer meer op in code, specs/, docs/ of yaml.
- `mvn clean compile` in newsfeedbackend/newsfeedbackend: BUILD SUCCESS, exitcode 0, 0 warnings.
- `mvn test`: BUILD SUCCESS, exitcode 0, 94 tests, 0 failures / 0 errors. De WARNING-regels in
  het log komen uit de test-compile ('asText() is deprecated' in SharedFeedE2eTest) en zijn
  pre-existing. De volledige `mvn verify` (e2e) draait de harness revisiegebonden na deze run.
- Preview pnf-pr-201 (https://pnf-pr-201.vdzonsoftware.nl), /api/version = sha 993df53 (= HEAD
  van deze branch). Backend/frontend/reader-pods Running.
- Inlogmodus: **fallback wegwerp-account** `tester_sf-1753`. De vaste test-user-creds waren niet
  beschikbaar: TESTER_USERNAME/TESTER_PASSWORD zijn niet gezet en
  `oc get secret newsfeed-api-keys -n pnf-pr-201` is Forbidden voor de claude-agent-SA. Account
  aan het eind verwijderd via DELETE /api/account/me (200, deleted=true; herlogin daarna 401).
- Auth-gedrag (raakt AuthService, waar userExists is verwijderd): registratie via de Flutter-UI OK,
  UI-login OK, dubbele registratie -> 409 "Username already in use", PUT /api/account/password met
  juist wachtwoord -> 200 en daarna login oud wachtwoord 401 / nieuw wachtwoord 200,
  DELETE /api/account/me -> 200. Geen enkele regressie door het wegvallen van userExists.
- Podcast-/AI-pad (raakt OpenAiChatHttpClient.doComplete zonder responseFormat en
  ExternalCallLogger.log): podcast-feed (The Vergecast) toegevoegd -> 200, ingestion draaide door,
  6 afleveringen met echte Nederlandse AI-samenvattingen + categorie-classificatie + feed-selectie
  in de RSS- en Feed-tab. Bewijs dat de OpenAI-chatclient na het schrappen van completeJson/
  responseFormat normaal werkt. Feed daarna weer verwijderd -> 200.
- RSS-instellingen: PUT/GET /api/rss-feeds OK; SSRF-validatie nog intact
  (http://127.0.0.1/feed -> 400 met NL-melding).
- UI-smoke met screenshots in /work/screenshots: login, Feed-, RSS-, Podcast- en
  Instellingen-tab renderen normaal, geen foutmeldingen.
- Geen bugs gevonden; geen code, tests of infra gewijzigd.
