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

## Tweede ronde: diagnose van de afgewezen factory-verificatie

De harness wees `backend-maven-verify` af (exitCode 1, 260 s). Bij het opnieuw draaien in deze ronde
kwam dezelfde storing één keer terug en is de oorzaak vastgesteld — **geen assertie en geen testcode**:

- Run A (`clean verify`): BUILD FAILURE, 116 unit groen, 76 e2e met 5 errors. Alle vijf errors zitten in
  `FeedE2eTest` (een bestaande klasse, niet aangeraakt door deze story) en zijn geen assertiefouten maar
  `IllegalState: Failed to load ApplicationContext` / `ApplicationContext failure threshold (1) exceeded`.
  De onderliggende oorzaak staat in de log van die fork:
  `RyukResourceReaper: Can not connect to Ryuk at host.docker.internal:63342` gevolgd door tientallen
  `java.net.SocketException: Broken pipe`. Testcontainers kreeg zijn resource-reaper niet verbonden en de
  Postgres-container van die fork kwam daardoor niet op. `RequestRecoveryE2eTest` was in diezelfde run
  gewoon groen (5/5, 12,1 s).
- Run B (`clean verify`): BUILD SUCCESS, exit 0, 116 unit + 76 e2e, 0 failures / 0 errors, 04:08 min.
- Run C (`clean verify`, direct erna): BUILD SUCCESS, exit 0, 116 unit + 76 e2e, 0 failures / 0 errors,
  04:26 min. `target/jacoco.exec` (334.990 B) en `target/jacoco-it.exec` (9.238.323 B) beide aanwezig;
  `grep -icE 'warning|deprecat|self-attach'` geeft 1 hit (de bekende `[Podcast]`-logregel).

Daarmee is aan AC9 voldaan met runs B en C: twee opeenvolgende `mvn -B --no-transfer-progress clean verify`
met exitcode 0 en 0 failures / 0 errors.

Conclusie en restrisico (bewust niet gerepareerd, valt buiten deze story): de e2e-suite start per testklasse
een eigen JVM-fork (`reuseForks=false`, `forkCount=1`) en dus per klasse een eigen Ryuk- én Postgres-container.
Op deze Docker-Desktop-via-socket-omgeving mislukt die Ryuk-handshake af en toe, en dan valt een willekeurige
e2e-klasse in zijn geheel om. Dat is infrastructuur, niet code: er zijn geen achtergebleven containers
(`GET /containers/json` toont er nul van de suite) en de storing trof een klasse die deze story niet raakt.
Een structurele oplossing (bijv. `reuseForks=true` of gedeelde container) vraagt een `pom.xml`-wijziging en
botst met AC8 van deze story; dat is stof voor een aparte story. Blijft de factory-verificatie hierop hangen,
dan is een herstart van de verificatie de eerste stap.

## Review (SF-2159)

Akkoord, geen blockers. Gecontroleerd op de volledige story-diff `main...HEAD` (2 bestanden: de nieuwe
testklasse + dit worklog):
- Testbewijs is revisiegebonden: `[FACTORY VERIFICATION EVIDENCE]` van het tweede developercomment staat op
  `status=passed / exitCode=0` met `testedTreeSha=33f4f52…`, exact gelijk aan `git rev-parse HEAD^{tree}`.
  Aanvullend nagelopen in de bewaarde reports: 116 unit + 76 e2e over 13 e2e-klassen, geen enkel report
  zonder `Failures: 0, Errors: 0`, en `TEST-…RequestRecoveryE2eTest.xml` bevat de vijf nieuwe testnamen.
- AC1-AC8 nagelopen en gehaald: erft van `E2eTestBase` met `@Autowired RequestService`, vijf `@Test`s met
  Nederlandse gedragsnamen, alle status-/`completedAt`-asserties uit `GET /api/requests`, commentaarregels
  bij geval 2 (§6.6) en geval 4 (`auth.listUsernames()`), geen assertie op totalen, geen latch, en geen
  wijziging in `src/main/`, `pom.xml` of `specs/`.
- Spec-check: §6.6 (regel 354-358 van `specs/backend-functional-spec.md`) dekt wat de tests vastleggen; de
  asserties komen overeen met `RequestServiceImpl.resetStuck()`. Geen Modulith-schending (alleen imports uit
  de publieke `request`-rootpackage), geen endpoint- of Flyway-wijziging, dus `specs/openapi.yaml` blijft
  terecht ongemoeid.
- [suggestie] Geval 3 asserteert de lege `completedAt` alleen op `daily-summary-…`, niet op
  `hourly-update-…`, terwijl de aannames in de story beide vaste verzoeken noemen. Waarschijnlijk bewust om
  de blootstelling aan de hardcoded `RssScheduler.hourlyRefresh`-cron te beperken; de statusassertie op
  `hourly-update-…` staat er wél, dus die blootstelling bestaat sowieso al. Niet blokkerend; eventueel de
  reden in één regel commentaar vastleggen.
- [info] Het Ryuk-restrisico uit de tweede ronde is correct gediagnosticeerd en buiten scope gehouden; blijft
  een terugkerende infra-flake voor de factory-gate.

## Test (SF-2160)

Akkoord — `tested`. Getest op branch `ai/SF-2158`, diff `main...HEAD` = 2 bestanden (de nieuwe testklasse +
dit worklog).

Vangnet (zelf gedraaid, tot het einde uitgelopen):
- `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`: **BUILD SUCCESS,
  exit 0**, 04:31 min. 116 unit + 76 e2e (13 e2e-klassen), `Failures: 0, Errors: 0, Skipped: 0` in beide
  `Results`-blokken.
- Hard nagerekend over alle 33 reportbestanden: `failures="0"` 33×, `errors="0"` 33×;
  `failsafe-summary.xml` geeft `<errors>0</errors><failures>0</failures>`. Geen rode test, dus de gate
  is gehaald zonder flake-protocol.
- `RequestRecoveryE2eTest`: 5 testcases, 12,09 s, groen.
- `target/jacoco.exec` (334.990 B) en `target/jacoco-it.exec` (9.239.988 B) beide geschreven — de
  `@{argLine}`-vangrail uit SF-2151 staat overeind.
- De Ryuk-flake uit de tweede developerronde trad in deze run niet op.

Acceptatiecriteria nagelopen:
- AC1-AC7: gecontroleerd in de testklasse — erft van `E2eTestBase`, `@Autowired RequestService`, vijf
  `@Test`s met Nederlandse gedragsnamen, alle status-/`completedAt`-asserties komen uit `GET /api/requests`
  met het token van de betreffende gebruiker (`requestById`), commentaarregel §6.6 bij geval 2 (r126-129) en
  `auth.listUsernames()`-toelichting bij geval 4 (r175-178), nergens een assertie op totaal/aantal (alleen
  de `size() == 2`-wachtconditie per eigen, net geregistreerde gebruiker), en geen latch: begintoestanden
  uitsluitend via `requestService.upsert(...)` met UUID-id's.
- AC8: `git diff main...HEAD --stat` raakt geen `src/main/`, geen `pom.xml` en geen `specs/`.
- AC9: hierboven, exit 0.
- AC10: de asserties komen 1-op-1 overeen met `RequestServiceImpl.resetStuck()` (r129-144) en met §6.6
  (`specs/backend-functional-spec.md:354-358`). Geen spec-afwijking gevonden.

Live corroboratie op de preview (`https://pnf-pr-228.vdzonsoftware.nl`), modus: **fallback wegwerp-account**
— `TESTER_USERNAME`/`TESTER_PASSWORD` zijn in deze harness niet gezet en het namespace-secret is niet
leesbaar (bekende `agent:local`-beperking). Met `tester_sf-2160` bevestigd dat `GET /api/requests` direct na
registratie exact de twee vaste verzoeken geeft, beide `status=DONE` met `completedAt=null`. Dat is precies
de aanname waar geval 3 op asserteert, nu ook buiten de testcontainer bewezen. Opgeruimd met
`DELETE /api/account/me` (200); cleanup geverifieerd met een herlogin die 401 geeft.

Geen browser-/screenshotbewijs: deze story voegt uitsluitend een backend-e2e-testklasse toe en heeft geen
frontend- of runtime-oppervlak (`resetStuck()` draait alleen bij serverstart en is niet via een endpoint aan
te roepen). De preview draait daardoor per definitie hetzelfde gedrag als main.

Bevindingen: geen. De suggestie van de reviewer over de ontbrekende `completedAt`-assertie op
`hourly-update-…` in geval 3 is niet blokkerend en niet in strijd met een AC; laten staan.
