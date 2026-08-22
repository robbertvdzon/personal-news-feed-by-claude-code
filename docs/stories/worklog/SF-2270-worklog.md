# SF-2270 - Worklog

Story-context bij eerste pickup:
Bump spring-boot-starter-parent naar 4.0.8 en leg dependency:tree-resultaat vast

Zet in newsfeedbackend/newsfeedbackend/pom.xml regel 10 de parent-versie van 4.0.7 naar 4.0.8 (komt exact 1x voor). Voeg GEEN nieuwe <version>-pin of versie-property toe voor log4j, postgresql of een van beide jackson-databind-artefacten; de BOM regelt dit (huisregel specs/backend-technical-spec.md:28). Draai vanuit newsfeedbackend/newsfeedbackend `mvn -B --no-transfer-progress clean test`: verwacht BUILD SUCCESS met Tests run: 142, Failures: 0, Errors: 0. Controleer dat `grep -icE 'warning|deprecat|self-attach'` over de volledige buildlog 0 blijft (harde eis sinds SF-2151; niet-nul = blokkade, ook bij groene tests). Draai daarna `mvn -B --no-transfer-progress dependency:tree` en noteer letterlijk de vier regels voor org.apache.logging.log4j:log4j-api (verwacht 2.25.5), org.postgresql:postgresql (42.7.13), tools.jackson.core:jackson-databind (3.1.5) en com.fasterxml.jackson.core:jackson-databind (2.21.5) in docs/stories/worklog/SF-2270-worklog.md. Blijf binnen de 4.0.x-lijn; 4.1.x valt buiten scope. Nul docwerk: specs/backend-technical-spec.md:28 blijft feitelijk juist. Let bij een onverwachte rode test op de 39 verschoven beheerde versies (Tomcat 11.0.24, Spring Framework 7.0.9, Spring Security 7.0.7). mvn verify/e2e is lokaal niet draaibaar (geen docker in de container). Sluit af met een zelfreview van de diff: die mag uitsluitend pom-regel 10 en het worklog raken.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `newsfeedbackend/newsfeedbackend/pom.xml` regel 10: `<version>4.0.7</version>` -> `<version>4.0.8</version>`.
  Eén regel, geen nieuwe `<version>`-pin en geen versie-property voor log4j, postgresql of een van beide
  jackson-databind-artefacten — de Spring Boot BOM regelt die vier (huisregel `specs/backend-technical-spec.md:28`).
  `grep -c '4\.0\.7' newsfeedbackend/newsfeedbackend/pom.xml` = 0.
- Geen code-, test-, frontend-, spec- of deploywijziging nodig: de bump is puur een beheerde-versiewissel en
  de bestaande 142 unit-tests + 77 e2e-tests zijn het vangnet. Ook nul docwerk — `specs/backend-technical-spec.md:28`
  blijft feitelijk juist.

## Verificatie (deze run, 2026-08-22)

- `mvn -B --no-transfer-progress clean test` vanuit `newsfeedbackend/newsfeedbackend`: **BUILD SUCCESS**,
  `Tests run: 142, Failures: 0, Errors: 0, Skipped: 0`, 30,9 s, log 331 regels.
- Ruisfilter over die volledige buildlog: `grep -icE 'warning|deprecat|self-attach'` = **0** (harde eis sinds SF-2151).
- `target/jacoco.exec` geschreven (373.564 B), dus de `@{argLine}`-keten uit SF-2151 is intact.
- `mvn -B --no-transfer-progress clean verify` (inclusief de e2e-suite op Testcontainers): **BUILD SUCCESS**, exit 0,
  142 unit + 77 e2e, 0 failures / 0 errors, 4:09 min, `target/jacoco-it.exec` 9.246.965 B. De story-aanname dat
  e2e lokaal niet draaibaar is klopte niet: `docker`-CLI ontbreekt wél, maar `/var/run/docker.sock` is bereikbaar,
  dus AC7 is lokaal al bevestigd naast CI. Ruisfilter op de verify-log geeft 1 hit en dat is de bekende, niet-regressieve
  Nederlandse `[Podcast] ... (zie [TTS]-warnings)`-logregel uit een e2e-test (zie SF-2151).

### `dependency:tree` na de bump (letterlijk uit `mvn -B --no-transfer-progress dependency:tree`)

```
[INFO] |  |  |  |  \- org.apache.logging.log4j:log4j-api:jar:2.25.5:compile
[INFO] +- org.postgresql:postgresql:jar:42.7.13:compile
[INFO] |  +- tools.jackson.core:jackson-databind:jar:3.1.5:compile
[INFO] |  \- com.fasterxml.jackson.core:jackson-databind:jar:2.21.5:compile
```

Daarmee zijn de vier artefacten uit de aanleiding op de doelversies waarvoor OSV.dev tijdens de audit leeg
terugkwam (log4j-api 2.25.4 -> 2.25.5, postgresql 42.7.11 -> 42.7.13, `com.fasterxml` jackson-databind
2.21.4 -> 2.21.5, `tools.jackson` jackson-databind 3.1.4 -> 3.1.5): 6 advisories -> 0.
