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
