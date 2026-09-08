# SF-2151 - [Audit] [Audit] Maak de build-uitvoer van de backend echt leeg: geef Mockito als Java-agent mee

## Story

[Audit] [Audit] Maak de build-uitvoer van de backend echt leeg: geef Mockito als Java-agent mee

<!-- refined-by-factory -->

## Scope

Eén bestand: `newsfeedbackend/newsfeedbackend/pom.xml`, uitsluitend de `<build><plugins>`-sectie. Geen productiecode, geen testcode, geen documentatie, geen andere bestanden.

Twee wijzigingen die samen de zes ruisregels op nul brengen, plus één meeliftende wijziging voor failsafe:

1. Voeg `maven-dependency-plugin` toe met een execution voor het goal `properties` (zonder expliciete `<phase>`; de default is `initialize`). Geen `<version>` nodig — die komt uit `spring-boot-starter-parent` (geverifieerd: `dependency:3.9.0:properties`).
2. Voeg aan de bestaande `maven-surefire-plugin`-`<configuration>` toe:
   `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>`
   (naast de bestaande `<excludes>`; die blijft ongewijzigd).
3. Voeg dezelfde `<argLine>`-regel toe aan de `<configuration>` van `maven-failsafe-plugin` (naast `reuseForks`/`forkCount`/`includes`). Dit is de e2e-tegenhanger; lokaal niet te verifiëren omdat Testcontainers Docker nodig heeft en die in de agent-omgeving ontbreekt. CI (`mvn -B --no-transfer-progress clean verify`, `.factory/verification.yaml`) is de bevestiging.

### Twee valkuilen — beide verplicht

**Valkuil 1 (luidruchtig).** Stap 1 is niet optioneel. De placeholder `${org.mockito:mockito-core:jar}` wordt door surefire alleen ingevuld voor *directe* dependencies; `mockito-core` komt hier transitief binnen via `spring-boot-starter-test`. Zonder de `properties`-stap gaat de placeholder letterlijk naar de JVM en crasht de fork met `Error opening zip file or JAR manifest missing` / `The forked VM terminated without properly saying goodbye`.

**Valkuil 2 (stil).** De `@{argLine}` aan het begin is niet optioneel. JaCoCo's `prepare-agent` zet zelf de property `argLine`; een hardgecodeerde `<argLine>` zonder `@{argLine}` overschrijft die. Gevolg: build groen, 116 tests groen, geen enkele melding — en `target/jacoco.exec` bestaat daarna niet meer. Daarom is AC3 hieronder verplicht te controleren.

## Acceptance criteria

Na `mvn -B --no-transfer-progress clean test` in `newsfeedbackend/newsfeedbackend`, met de log opgeslagen in een bestand:

1. `BUILD SUCCESS` en `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`.
2. `grep -icE 'warning|deprecat|self-attach'` op de opgeslagen build-log geeft `0`. Dit is de kern van de story en moet expliciet aangetoond worden (uitkomst in de PR/worklog vermelden).
3. `target/jacoco.exec` bestaat en is ongeveer even groot als voorheen (~335 KB; baseline 336387 B, met fix 335057 B). Niet overslaan — dit dekt valkuil 2 af.
4. `git status`/`git diff --stat` laat exact één gewijzigd bestand zien: `newsfeedbackend/newsfeedbackend/pom.xml` (afgezien van het factory-worklog).
5. De failsafe-`<argLine>` is aanwezig met dezelfde `@{argLine}`-prefix als bij surefire. Deze wordt niet lokaal gedraaid; groene CI (`mvn clean verify`) is het bewijs.

## Aannames

- Gemeten uitgangswaarden zijn in een kopie van deze checkout gereproduceerd (2026-08-14, JDK 21.0.11 Temurin): vóór de fix 6 ruisregels / 116 tests / jacoco.exec 336387 B; ná de fix 0 ruisregels / 116 tests / jacoco.exec 335057 B. Dit is geen schatting.
- Dit is geen regressie en geen gedragswijziging voor de applicatie: alleen de manier waarop de test-JVM start verandert.
- `-Xshare:off` is nodig om de `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes`-regel weg te nemen die ontstaat door het toevoegen van de javaagent; het kost een verwaarloosbare hoeveelheid starttijd (gemeten totale testrun bleef ~44 s).
- Er is geen duplicaatstory in `origin/main` (git log gecontroleerd t/m `f8669aa`).
- Als CI's `verify` alsnog breekt op de coverage- of e2e-stap, is de failsafe-`<argLine>` (punt 3 in Scope) de eerste plek om te kijken; dat is dan een vervolgactie, geen reden om de surefire-fix terug te draaien.

## Eindsamenvatting

Alles is gelezen: `.task.md`, het worklog (`docs/stories/worklog/SF-2151-worklog.md`) en de daadwerkelijke diff.

## Eindsamenvatting SF-2151

**Wat is gebouwd**

Eén bestand gewijzigd: `newsfeedbackend/newsfeedbackend/pom.xml` (+30 regels, alleen `<build><plugins>`). Drie samenhangende aanpassingen:

1. `maven-dependency-plugin` toegevoegd met een execution voor het goal `properties` (default-phase `initialize`, geen `<version>` — komt uit `spring-boot-starter-parent`, resolvet naar `dependency:3.9.0`).
2. `maven-surefire-plugin` krijgt `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>` naast de ongewijzigde `<excludes>`.
3. Dezelfde `<argLine>` op `maven-failsafe-plugin` voor de e2e-suite.

**Keuzes en waarom**

- De `dependency:properties`-stap is nodig omdat surefire de jar-placeholder alleen invult voor *directe* dependencies; `mockito-core` komt hier transitief via `spring-boot-starter-test`. Zonder die stap crasht de test-fork.
- De `@{argLine}`-prefix is bewust behouden: JaCoCo's `prepare-agent` zet die property zelf, en een harde `<argLine>` zou de coverage-agent stil overschrijven (build blijft groen, `jacoco.exec` verdwijnt). Dit is expliciet gecontroleerd.
- `-Xshare:off` neemt de CDS-waarschuwing weg die het toevoegen van een javaagent oproept; kost verwaarloosbare starttijd.
- Geen productie-, test- of documentatiecode aangeraakt: dit is buildconfiguratie zonder gedragswijziging.

**Wat is getest**

- `mvn clean test`: BUILD SUCCESS, `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`.
- Kern-AC: `grep -icE 'warning|deprecat|self-attach'` op de build-log geeft **0** (baseline 6 ruisregels).
- `target/jacoco.exec` bestaat en is 334.990 B (baseline 336.387 B) — coverage-agent draait dus nog steeds mee.
- Extra, buiten de AC's: `mvn clean verify` lokaal gedraaid (Docker-socket bleek beschikbaar) → exit 0, 116 unit + 71 e2e groen, zowel `jacoco.exec` als `jacoco-it.exec` (8,5 MB) geschreven. Daarmee is de failsafe-variant niet alleen statisch maar ook draaiend bewezen; dat hoefde CI niet meer te ontdekken.
- Preview `pnf-pr-227`: `/api/version`, `/actuator/health` en `/api/shared/feed` alle 200 — rooksignaal dat de app onaangeraakt is.
- Reviewer bevestigde de fasevolgorde in de log (`dependency:properties` vóór `jacoco:prepare-agent`) en het harnessbewijs (`backend-maven-verify` passed, `testedTreeSha` matcht de commit).

**Bewust niet gedaan**

- Geen nieuwe tests: de 116 bestaande unittests (+71 e2e) zijn de dekking voor een buildconfig-wijziging.
- Geen aparte `docs/stories/SF-2152-*.md`, omdat de acceptatiecriteria exact één gewijzigd bestand naast het worklog eisten; het worklog is de story-log.
- Geen browser-/screenshotbewijs: er is geen UI-pad dat door deze wijziging geraakt wordt.

**Kanttekening (geen blocker):** de `verify`-log bevat 1 grep-treffer op "warning", maar dat is een applicatie-logregel uit een bewust negatief e2e-testpad (`[Podcast]`, gemockte TTS) — geen buildruis. Het acceptatiecriterium is op `clean test` gedefinieerd en daar is de teller 0.

Geen blockers, geen bugs, geen flakes.
