# SF-2158 - Worklog

Story-context bij eerste pickup:
RequestRecoveryE2eTest toevoegen voor startup-herstel van vastgelopen verzoeken

Voeg uitsluitend het nieuwe bestand newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/RequestRecoveryE2eTest.kt toe. Erf van E2eTestBase, injecteer RequestService met @Autowired (resetStuck() en upsert(username, request) staan beide op de interface). Wijzig geen productiecode, geen pom.xml en geen specs/.

Per test: registreer een eigen gebruiker met registerUser(...), zet begintoestanden rechtstreeks met requestService.upsert(...) (id's in UUID-vorm), roep requestService.resetStuck() aan en lees het resultaat terug via GET /api/requests met het token van die gebruiker - niet via de repository, zodat de serialisatie meeloopt. Elk geval is een eigen @Test met een Nederlandse, gedragsbeschrijvende naam in de stijl van de bestaande e2e-suite.

Vijf gevallen:
1. PROCESSING wordt FAILED, met gevulde completedAt (resetStuck zet Instant.now()); dat onderscheidt een echte reset van een al gefaald verzoek.
2. PENDING wordt ook FAILED, met een commentaarregel die naar spec-paragraaf 6.6 van specs/backend-functional-spec.md verwijst.
3. Afgeronde verzoeken blijven met rust: een DONE- en een CANCELLED-verzoek houden status en oorspronkelijke completedAt. Neem de twee vaste verzoeken (hourly-update-<user>, daily-summary-<user>) mee: die blijven DONE. Let op: ensureFixedRequests maakt ze aan met completedAt = null, dus asserteer daar op een afwezige/lege completedAt, niet op een waarde. Wacht eerst tot GET /api/requests twee items geeft (patroon FixedRequestsE2eTest regels 60-65), anders is dit geval flaky omdat ensureFixedRequests via UserRegisteredListener asynchroon loopt.
4. De reset raakt alle gebruikers: registreer een tweede gebruiker met een eigen PROCESSING-verzoek en assert dat ook die op FAILED staat. Zet er een commentaarregel bij dat dit bewust gedrag is (auth.listUsernames(), startup-herstel, geen gebruikersactie).
5. Idempotentie: een tweede resetStuck() direct erna verandert niets - statussen blijven FAILED en de completedAt van de eerste reset blijft staan.

Risico's die je moet afvangen: (a) resetStuck() werkt over alle gebruikers in de database, ook die van eerder gedraaide tests in dezelfde klasse - asserteer nooit op de totale lijst of het totale aantal, filter altijd op de eigen gebruiker(s) en eigen request-id's; (b) houd nooit een echt lopend verzoek met een latch in PROCESSING vast (race met de orkestrator die de status alsnog op DONE zet) - gebruik uitsluitend upsert; (c) RssScheduler.hourlyRefresh heeft een hardcoded cron 0 0 * * * * die niet uit staat in E2eTestBase en bij een run precies over het hele uur het hourly-update-verzoek kan aanraken (bekend risico, zie RequestWebSocketE2eTest rond regel 100) - beperk de blootstelling door direct na resetStuck() te asserteren en per id te filteren; voeg geen nieuwe cron-property toe.

Afronding: doe zelf een review-slag over de nieuwe testklasse (naamgeving, commentaarregels bij geval 2 en 4, geen assertie op totalen, geen productiecode-diff). Draai mvn -B --no-transfer-progress clean verify in newsfeedbackend/newsfeedbackend twee keer achter elkaar en zet de uitkomst van beide runs in het worklog. Is Docker in de omgeving niet beschikbaar (failsafe/Testcontainers dan niet lokaal draaibaar), meld dat expliciet in het worklog samen met de wel gedraaide mvn clean test-uitkomst; CI (.factory/verification.yaml, backend-maven-verify) is dan de bevestiging. Controleer met git diff dat er geen wijzigingen in src/main/, pom.xml of specs/ zitten. Legt een test een echt verschil met de specificatie bloot, meld dat expliciet in het worklog en pas de assertie noch de productiecode aan - dat is stof voor een aparte story.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw bestand `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/RequestRecoveryE2eTest.kt`
  (enige codewijziging): 5 tests met Nederlandse, gedragsbeschrijvende namen, erft van `E2eTestBase`,
  injecteert `RequestService` met `@Autowired`.
- Begintoestanden uitsluitend via `requestService.upsert(...)` met UUID-vormige id's; geen latch en geen
  echt lopend verzoek in `PROCESSING`. Alle asserties op status/`completedAt` komen uit de respons van
  `GET /api/requests` met het token van de betreffende gebruiker.
- Elke test registreert een eigen gebruiker en wacht eerst tot `GET /api/requests` de twee vaste verzoeken
  toont (patroon `FixedRequestsE2eTest.registerUserWithFixedRequests`). Dat is niet alleen voor geval 3
  nodig: `ensureFixedRequests` eindigt in `repo.save(...)`, dat eerst alle rijen van de gebruiker verwijdert —
  zonder dat anker kan die async stap een net geseed verzoek weer wegvagen (echte race, niet theoretisch).
- Geen enkele test asserteert op de totale lijst of het totale aantal; er wordt altijd per gebruiker en per
  request-id gefilterd, en direct na `resetStuck()` gelezen (beperkt de blootstelling aan de hardcoded
  `RssScheduler`-cron). Er is geen cron-property toegevoegd.
- Geval 1 legt de beginsituatie (`completedAt` leeg) eerst via HTTP vast en asserteert daarna dat de gevulde
  waarde niet vóór het moment van de reset ligt — zo bewijst de test dat `resetStuck` de waarde zette.
- Geval 2 heeft een commentaarregel met de verwijzing naar §6.6 van `specs/backend-functional-spec.md`;
  geval 4 heeft een commentaarregel dat de reset bewust over alle gebruikers loopt (`auth.listUsernames()`,
  startup-herstel via `RequestBootstrap`, geen gebruikersactie).
- Geval 3 asserteert voor de twee vaste verzoeken op status `DONE` én op een lege `completedAt`
  (`ensureFixedRequests` maakt ze zonder `completedAt` aan), en voor het eigen `DONE`/`CANCELLED`-verzoek op
  een onveranderde `completedAt` (string-vergelijking van de JSON-waarde, dus geen aannames over precisie).
- Geen spec-afwijking gevonden: het waargenomen gedrag komt exact overeen met §6.6 (`PENDING`/`PROCESSING`
  → `FAILED`, afgeronde verzoeken ongemoeid). Er is dus niets afgezwakt of gerepareerd.
- Geen wijziging in `src/main/`, `pom.xml` of `specs/`; `git status` toont alleen dit worklog en de nieuwe
  testklasse.

Verificatie (Docker was wél beschikbaar via `/var/run/docker.sock`, dus de failsafe/Testcontainers-fase is
lokaal gedraaid):
- Run 1 `mvn -B --no-transfer-progress clean verify`: BUILD SUCCESS, exit 0, 116 unit + 76 e2e
  (was 71), 0 failures / 0 errors, 04:03 min.
- Run 2 `mvn -B --no-transfer-progress clean verify`: BUILD SUCCESS, exit 0, 116 unit + 76 e2e,
  0 failures / 0 errors, 03:59 min.
- `grep -icE 'warning|deprecat|self-attach'` geeft in beide logs 1 hit; dat is de bekende, niet door deze
  story veroorzaakte `[Podcast] … (zie [TTS]-warnings)`-logregel uit `PodcastGenerationE2eTest`.
- `target/jacoco.exec` (334.990 B) en `target/jacoco-it.exec` (9.238.282 B) zijn beide geschreven — de
  `@{argLine}`-vangrail uit SF-2151 staat nog overeind.
