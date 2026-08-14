# SF-2151 - Worklog

Story-context bij eerste pickup:
pom.xml: Mockito als Java-agent meegeven en build-ruis op nul brengen

Wijzig uitsluitend newsfeedbackend/newsfeedbackend/pom.xml, sectie <build><plugins>:

1. Voeg maven-dependency-plugin toe met één execution voor het goal `properties`, zonder expliciete <phase> (default initialize, draait vóór jacoco:prepare-agent) en zonder <version> (komt uit spring-boot-starter-parent; resolvet naar dependency:3.9.0). Dit is NIET optioneel: mockito-core komt transitief via spring-boot-starter-test binnen, dus surefire vult de placeholder anders niet in en de fork crasht met 'Error opening zip file or JAR manifest missing'.
2. Voeg aan de bestaande <configuration> van maven-surefire-plugin toe (naast de bestaande <excludes>, die ongewijzigd blijft):
   <argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>
   De @{argLine}-prefix is verplicht: JaCoCo's prepare-agent zet de property argLine; zonder prefix wordt die stil overschreven en verdwijnt target/jacoco.exec zonder foutmelding.
3. Voeg dezelfde <argLine>-regel toe aan de bestaande <configuration> van maven-failsafe-plugin (naast reuseForks/forkCount/includes). Lokaal niet verifieerbaar (Testcontainers vereist Docker, ontbreekt in de container); CI `mvn clean verify` (.factory/verification.yaml, backend-maven-verify) is de bevestiging.

Geen productiecode, geen testcode, geen documentatie, geen nieuwe tests - dit is buildconfiguratie zonder gedragswijziging; de 116 bestaande tests zijn de dekking.

Verifieer zelf en rapporteer de uitkomsten in worklog/PR: draai `mvn -B --no-transfer-progress clean test` in newsfeedbackend/newsfeedbackend met de log in een bestand en toon aan: (a) BUILD SUCCESS en `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`; (b) `grep -icE 'warning|deprecat|self-attach'` op die log geeft 0 (baseline 6); (c) target/jacoco.exec bestaat en is ~335 KB (baseline 336387 B, met fix 335057 B); (d) `git diff --stat` toont exact één gewijzigd bestand, pom.xml (afgezien van het factory-worklog). Formuleer de grep-controle nooit op 'WARN': de SSRF-unittests loggen legitiem logback-WARN-regels die niet op 'warning' matchen. Sluit af met de ingebouwde review-stap.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `newsfeedbackend/newsfeedbackend/pom.xml`, `<build><plugins>` (enige gewijzigde bestand):
  1. `maven-dependency-plugin` toegevoegd met één execution voor goal `properties`
     (geen expliciete `<phase>` → default `initialize`, dus vóór `jacoco:prepare-agent`;
     geen `<version>` → komt uit `spring-boot-starter-parent`, resolvet naar
     `dependency:3.9.0:properties`). Nodig omdat surefire de placeholder
     `${org.mockito:mockito-core:jar}` alleen voor *directe* dependencies invult en
     mockito-core hier transitief via `spring-boot-starter-test` binnenkomt.
  2. `maven-surefire-plugin` `<configuration>`: `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>`
     toegevoegd naast de ongewijzigde `<excludes>`.
  3. Dezelfde `<argLine>` toegevoegd aan `maven-failsafe-plugin` `<configuration>`
     (naast `reuseForks`/`forkCount`/`includes`).
  De `@{argLine}`-prefix is bewust behouden zodat de JaCoCo-agent niet stil wordt
  overschreven; `-Xshare:off` haalt de CDS-waarschuwing weg die de javaagent oproept.

Verificatie (JDK 21, agent-container, 2026-08-14):
- `mvn -B --no-transfer-progress clean test` → exitcode 0, `BUILD SUCCESS`,
  `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`.
- `grep -icE 'warning|deprecat|self-attach'` op die build-log → **0** (baseline 6). AC2 gehaald.
- `target/jacoco.exec` bestaat en is 334.990 B (~335 KB; baseline vóór fix 336.387 B,
  verwacht ná fix ~335.057 B). Valkuil 2 dus afgedekt: JaCoCo schrijft nog steeds.
- `git diff --stat` → exact één gewijzigd bestand: `newsfeedbackend/newsfeedbackend/pom.xml`
  (+30 regels), naast dit untracked factory-worklog.
- Extra, buiten de AC's: het volledige vangnet `mvn -B --no-transfer-progress clean verify`
  is óók gedraaid → exitcode 0, 116 unit + 71 e2e groen, 3:37 min. Docker was via
  `/var/run/docker.sock` beschikbaar, dus de failsafe-`<argLine>` (AC5) is hiermee
  wél lokaal bevestigd: zowel `target/jacoco.exec` (334.989 B) als
  `target/jacoco-it.exec` (8.532.892 B) is aangemaakt, dus `@{argLine}` werkt in
  beide plugins. De verify-log heeft 1 grep-hit, maar dat is een legitieme
  applicatie-logregel uit een e2e-test (het woord "warnings" in een Nederlandse
  `[Podcast]`-melding), geen buildruis; de AC-grep is op `clean test` gedefinieerd.

Geen productie-, test- of documentatiewijzigingen: dit is buildconfiguratie zonder
gedragswijziging, de 116 bestaande unittests (+71 e2e) zijn de dekking. Er is
bewust géén `docs/stories/SF-2152-*.md` aangemaakt omdat AC4 exact één gewijzigd
bestand naast het worklog eist; dit worklog is de story-log.

Review (SF-2152, 2026-08-14):
- Harnessbewijs `[FACTORY VERIFICATION EVIDENCE]`: `backend-maven-verify` passed
  (exit 0, 213s); `testedTreeSha` 9ef0f6c… komt exact overeen met
  `git rev-parse HEAD^{tree}` van de developercommit, dus het groene vangnet hoort
  bij deze revisie.
- Zelf nagedraaid (gerichte check, zonder `clean` om het bestaande bewijs te
  bewaren): `mvn -B --no-transfer-progress -o test` → exit 0, BUILD SUCCESS,
  `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`,
  `grep -icE 'warning|deprecat|self-attach'` op de log → **0** (AC1/AC2 bevestigd).
- Fasevolgorde bevestigd in de log: `dependency:3.9.0:properties (properties)`
  draait vóór `jacoco:0.8.13:prepare-agent`, en die zet `argLine` daarna nog steeds
  (`argLine set to -javaagent:…org.jacoco.agent…=destfile=…/target/jacoco.exec`);
  `target/jacoco.exec` wordt geschreven → valkuil 2 (`@{argLine}`) afgedekt (AC3).
- Scope: `git diff main...HEAD --stat` toont alleen `pom.xml` (+30) en dit worklog
  (AC4). Geen endpoint-, Flyway- of Spring Modulith-impact.
- AC5: failsafe-`<argLine>` aanwezig met dezelfde `@{argLine}`-prefix;
  `target/jacoco-it.exec` (8,5 MB) uit de verify-run bevestigt dat.
- Geen blockers of bugs gevonden.
