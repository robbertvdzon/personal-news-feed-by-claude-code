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

## Review (SF-2271, ronde 1)

- Revisie-anker: `git rev-parse HEAD^{tree}` = `ba62139f…` == `testedTreeSha` uit
  `[FACTORY VERIFICATION EVIDENCE]` (`backend-maven-verify` status=passed, exit 0). Bewijs hoort bij deze diff.
- AC1/AC2: diff raakt exact pom-regel 10 + dit worklog; `grep -c '4\.0\.7'` op de pom = 0;
  geen nieuwe `<version>`-pin of versie-property voor log4j/postgresql/jackson.
- AC5: geverifieerd uit de fat jar van deze revisie (`unzip -l target/newsfeedbackend-1.0.0.jar`):
  log4j-api-2.25.5, postgresql-42.7.13, jackson-databind-3.1.5 en jackson-databind-2.21.5 in `BOOT-INF/lib`.
- AC3/AC4 zelf herdraaid (`mvn -B --no-transfer-progress -o test`): BUILD SUCCESS,
  `Tests run: 142, Failures: 0, Errors: 0, Skipped: 0`, ruisfilter = 0.
- AC6: de vier `dependency:tree`-regels staan letterlijk in dit worklog. AC7 gedekt door de harness-run.
- [suggestie] voor de documentatie-subtaak: `specs/openapi.yaml:1316` heeft nog `example: 4.0.7` bij
  `springVersion`. Puur een spec-voorbeeld (waarde komt runtime uit `SpringBootVersion.getVersion()`),
  dus geen blocker en buiten scope van deze subtaak.

Besluit: akkoord.

## Test (SF-2272, story-brede test)

Getest op HEAD `12534b0` met schone working tree. Modus: **wegwerp-account**
`tester_sf-2270` — `TESTER_USERNAME`/`TESTER_PASSWORD` zijn in deze harness niet gezet en
`oc get secret newsfeed-api-keys -n pnf-pr-241` geeft Forbidden voor
`system:serviceaccount:agent-access:claude-agent` (bekend harness-feit).

### AC-verificatie

| AC | Bewijs | Uitkomst |
| --- | --- | --- |
| 1 | `git diff main...HEAD --stat` = alleen `pom.xml` (1 regel, `4.0.7`->`4.0.8`) + dit worklog; `grep -c '4\.0\.7'` op de pom = 0 | ✅ |
| 2 | `<properties>` (regel 20-31) bevat alleen java/kotlin/jjwt/rome; geen `<version>`-pin voor log4j, postgresql of beide jackson-databind-artefacten | ✅ |
| 3 | `mvn -B --no-transfer-progress clean test` = exit 0, BUILD SUCCESS, `Tests run: 142, Failures: 0, Errors: 0, Skipped: 0`; 24 surefire-reports alle `failures="0" errors="0"` | ✅ |
| 4 | `grep -icE 'warning\|deprecat\|self-attach'` over de volledige `clean test`-log (332 regels) = **0** | ✅ |
| 5 | `mvn -B --no-transfer-progress dependency:tree` = exit 0 met log4j-api 2.25.5, postgresql 42.7.13, `tools.jackson` jackson-databind 3.1.5, `com.fasterxml` jackson-databind 2.21.5 | ✅ |
| 6 | De vier regels hierboven zijn byte-identiek aan regel 39-42 van dit worklog | ✅ |
| 7 | `mvn -B --no-transfer-progress clean verify` lokaal herdraaid: exit 0, 142 unit + 77 e2e, 37 reportbestanden alle 0/0, `failsafe-summary.xml` completed=77 errors=0 failures=0, 4:05 min | ✅ |

### Live gedragstest op de preview (`https://pnf-pr-241.vdzonsoftware.nl`)

`GET /api/version` geeft `springVersion: "4.0.8"` — de bump draait echt op de preview.
Pods draaien de story-images (eerst `sha-fae97b1`, tijdens de run door ArgoCD vervangen door
`sha-12534b0`; de reviewercommit raakt alleen dit worklog, dus eerder bewijs blijft geldig).

Gericht op de vier gebumpte artefacten (postgres-driver, beide Jackson-lijnen, log4j) is de hele
auth/DB/JSON-keten doorlopen met het wegwerp-account:

- `POST /api/auth/register` -> 201 met JWT (DB-write + Jackson + jjwt-ondertekening)
- `POST /api/auth/login` -> 200, sleutels exact `[role, token, username]`
- `GET /api/settings` met geldig token -> 200 met JSON-body; zonder token -> 403; met kapot token -> 403
- `GET /api/rss` -> 200, `GET /api/requests` -> 200 met user-scoped ids `daily-summary-tester_sf-2270`
- `GET /api/admin/users` met user-token -> 403 (rol-autorisatie intact)
- `GET /api/shared/feed` en `/actuator/health` (permitAll) -> 200

Opruimen: `DELETE /api/account/me` -> 200, herlogin -> 401. Geen testdata achtergelaten, geen DB-mutatie
buiten dit wegwerp-account.

Screenshots in `/work/screenshots/`: `SF-2270-preview-login.png` (Flutter-loginscherm rendert),
`SF-2270-api-version-springboot-408.png` (`springVersion: 4.0.8` live).

### Opmerkingen

- Ruisfilter op de **verify**-log geeft 1 hit: de bewuste negatieve `[Podcast] no audio produced ...
  alle 4 TTS-calls faalden`-applicatielogregel uit `PodcastGenerationE2eTest`. AC4 slaat op de
  `clean test`-log en die is 0. Geen regressie.
- Geen flakes waargenomen; alle runs waren in één keer groen.
- Doorgeven aan SF-2274 (documentatie): `specs/openapi.yaml:1316` heeft nog `example: 4.0.7` bij
  `springVersion`, terwijl de live respons 4.0.8 teruggeeft. Puur een spec-voorbeeld, buiten scope
  van deze story — de reviewer signaleerde dit al.

Besluit: **tested** — alle 7 acceptatiecriteria aantoonbaar gehaald, vangnet volledig groen (exit 0,
0 failures, 0 errors).
