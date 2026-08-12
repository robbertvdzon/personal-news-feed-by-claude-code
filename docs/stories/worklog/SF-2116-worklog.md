# SF-2116 - Worklog

Story-context bij eerste pickup:
Bump spring-boot-starter-parent naar 4.0.7 in de backend-POM

Wijzig in `newsfeedbackend/newsfeedbackend/pom.xml` regel 10 de versie van `spring-boot-starter-parent` van `4.0.6` naar `4.0.7`. Dat is de ENIGE inhoudelijke wijziging: raak geen andere regel in de POM aan, voeg GEEN enkele nieuwe expliciete `<version>`-pin toe (AC5) en wijzig geen code, tests of docs - alle betrokken componenten worden door de parent gemanaged. Er zijn geen nieuwe of gewijzigde tests nodig: het gedrag van de applicatie verandert niet.

Verifieer daarna zelf, vanuit `newsfeedbackend/newsfeedbackend`:
1. `mvn -B --no-transfer-progress clean test` -> verwacht `Tests run: 116, Failures: 0, Errors: 0`, BUILD SUCCESS (AC2). Eerste run kan alles downloaden en enkele minuten duren; wacht die af binnen je beurt.
2. `mvn dependency:tree` -> controleer minimaal `tomcat-embed-core` 11.0.22 en `spring-websocket` 7.0.8, en bij voorkeur de hele tabel uit de story: spring-core/-web/-webmvc/-expression 7.0.8, logback-core 1.5.34, micrometer-core 1.16.6, postgresql 42.7.11, `tools.jackson.core:jackson-databind` 3.1.4, `com.fasterxml.jackson.core:jackson-databind` 2.21.4 (AC4).
3. `mvn -B clean verify` (failsafe/e2e) vereist Docker/Testcontainers. In de agent-container is `docker` NIET beschikbaar; als dat ook bij jou zo is, meld dat expliciet in je verslag en laat AC3 over aan de factory-verificatiepipeline (`.factory/verification.yaml`, job `backend-maven-verify`). Probeer AC3 niet te omzeilen door e2e-tests te wijzigen of uit te sluiten.
4. Controleer `git diff`: buiten de POM-regel (en de verplichte worklog-/docsstappen van de factory) mag er niets wijzigen (AC1).

Sluit af met een korte zelfreview van de diff. Loopt de build onverwacht stuk, pin dan NIETS vast maar rapporteer de concrete foutmelding.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `newsfeedbackend/newsfeedbackend/pom.xml` regel 10: `spring-boot-starter-parent` 4.0.6 -> 4.0.7.
  Dat is de enige inhoudelijke wijziging; geen nieuwe expliciete `<version>`-pin (AC5), geen code-,
  test- of docwijziging (het gedrag verandert niet).
- `mvn -B --no-transfer-progress clean test`: BUILD SUCCESS, Tests run: 116, Failures: 0, Errors: 0,
  0 `[WARNING]`-regels (AC2).
- `mvn dependency:tree`: alle zeven regels uit de story-tabel exact zoals verwacht, incl.
  tomcat-embed-core 11.0.22 en spring-websocket 7.0.8 (AC4).
- `mvn -B --no-transfer-progress clean verify`: BUILD SUCCESS in ~3:34, 116 unit + 71 e2e, 0 failures/errors (AC3).
  De `docker`-CLI ontbreekt in deze container, maar `/var/run/docker.sock` is aanwezig, dus Testcontainers
  draait gewoon; AC3 hoefde niet aan de factory-pipeline te worden overgelaten.
- `git diff` bevat buiten de POM-regel alleen de story-/worklog-bestanden (AC1).

Review (SF-2117, reviewer):
- Volledige story-diff (`git diff main...HEAD`) beoordeeld: 3 bestanden, exact één inhoudelijke
  regel (`pom.xml:10`, 4.0.6 -> 4.0.7), verder alleen story-log + worklog. AC1 en AC5 bevestigd:
  geen andere POM-regel geraakt, alle overige `<version>`-elementen in de POM zijn pre-existing.
- Testbewijs onafhankelijk nagerekend in `newsfeedbackend/newsfeedbackend/target/`: surefire 116,
  failsafe 71 over 12 klassen (kruischeck `grep -h @Test .../e2e/*E2eTest.kt` = 71), en
  `grep -hL "Failures: 0, Errors: 0" target/*-reports/*.txt` is leeg -> alles groen (AC2, AC3).
- AC4 hard bevestigd zonder `dependency:tree` te herdraaien: `unzip -l target/newsfeedbackend-1.0.0.jar`
  toont in BOOT-INF/lib exact tomcat-embed-core 11.0.22, spring-core/-web/-webmvc/-websocket/-expression
  7.0.8, logback-core 1.5.34, micrometer-core 1.16.6, postgresql 42.7.11, jackson-databind 3.1.4 en
  2.21.4. Dit bewijst tevens dat het groene vangnet bij DEZE revisie hoort (die versies resolven
  alleen onder parent 4.0.7).
- [suggestie] `specs/openapi.yaml:1283` heeft `example: 4.0.6` bij `springVersion`. De waarde zelf komt
  runtime uit `SpringBootVersion.getVersion()` (VersionController.kt:23), dus functioneel klopt alles;
  alleen het voorbeeld in de spec is nu verouderd. Buiten de scope van deze development-subtaak —
  meenemen in de documentatie-subtaak SF-2120.

Test (SF-2118, tester):
- Vangnet zelf gedraaid vanuit `newsfeedbackend/newsfeedbackend`:
  `mvn -B --no-transfer-progress clean verify` -> BUILD SUCCESS, exitcode 0, 03:41 min.
  Surefire `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0` (AC2), failsafe/e2e
  `Tests run: 71, Failures: 0, Errors: 0, Skipped: 0` (AC3). 0 `^[WARNING]`-regels.
  De `docker`-CLI ontbreekt in de tester-container (`docker info` -> exit 127), maar
  `/var/run/docker.sock` is aanwezig, dus Testcontainers draait rechtstreeks tegen de
  daemon; AC3 is dus lokaal aangetoond en hoefde niet aan de pipeline te worden gelaten.
- AC4 herhaald met een verse `mvn dependency:tree`: alle elf artefacten exact zoals in de
  story-tabel (tomcat-embed-core 11.0.22, spring-core/-web/-webmvc/-websocket/-expression
  7.0.8, logback-core 1.5.34, micrometer-core 1.16.6, postgresql 42.7.11,
  tools.jackson jackson-databind 3.1.4, fasterxml jackson-databind 2.21.4).
- AC1/AC5: `git diff main...HEAD` = 3 bestanden, exact één inhoudelijke regel (`pom.xml:10`).
  Het aantal `<version>`-elementen in de POM is 20 op zowel main als HEAD -> geen nieuwe pin.
  Werkboom na afloop schoon (`git status --porcelain` leeg).
- Gedragsverificatie op de preview (`https://pnf-pr-223.vdzonsoftware.nl`, pods draaien
  `sha-79c8932` = de developer-commit; de latere reviewer-commit raakt alleen docs):
  - `GET /api/version` -> `springVersion: "4.0.7"`: de bump is runtime zichtbaar.
  - `GET /api/shared/feed` -> 200, 543 items, 17-key-union, mediaTypes ARTICLE/PODCAST:
    pgjdbc 42.7.11 + jackson 3.1.4 serialiseren ongewijzigd.
  - `GET /actuator/prometheus` -> 200, 184 metricregels incl. correct getemplatede
    `http_server_requests_seconds_count{uri="/api/shared/feed"}` en hikaricp-metrics:
    de HTTP-instrumentatie van micrometer 1.16.6 (CVE-2026-40984) werkt normaal.
  - `wss://.../ws/requests` -> verbinding open + `serverVersion`-frame met sha 79c8932:
    spring-websocket 7.0.8 (CVE-2026-41838) functioneert ongewijzigd.
  - Geauthenticeerde lees- en schrijfpaden met een wegwerp-user: `/api/requests`,
    `/api/settings`, `/api/rss-feeds`, `/api/podcasts`, `/api/rss` alle 200;
    `PUT /api/rss-feeds` -> 200 en de waarde komt terug uit een verse GET.
  - Flutter-UI via Playwright: registratie/login door de UI heen, feedscherm rendert met
    tabs en navigatie, 0 console-errors en 0 HTTP>=400. Screenshots in `/work/screenshots`
    (`01-login.png`, `02-credentials.png`, `03-after-login.png`).
- Inlogmodus: **fallback wegwerp-account**, want `TESTER_USERNAME`/`TESTER_PASSWORD` waren
  niet gezet en `oc get secret newsfeed-api-keys -n pnf-pr-223` geeft Forbidden voor de
  `claude-agent`-SA. Testdata opgeruimd: `DELETE /api/account/me` -> 200, herlogin -> 401.
- Geen flakes waargenomen; geen enkele test hoefde herdraaid te worden.
