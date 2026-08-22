# SF-2270 - [Audit] Bump spring-boot-starter-parent van 4.0.7 naar 4.0.8

## Story

[Audit] Bump spring-boot-starter-parent van 4.0.7 naar 4.0.8

<!-- refined-by-factory -->

## Scope

Eén regelwijziging in `newsfeedbackend/newsfeedbackend/pom.xml` regel 10: de versie van `spring-boot-starter-parent` van `4.0.7` naar `4.0.8`.

Aanleiding: OSV.dev meldt op de huidige dependency-keten 6 advisories over 4 artefacten:
- `org.apache.logging.log4j:log4j-api` 2.25.4 (GHSA-qv9r-c865-cp47)
- `org.postgresql:postgresql` 42.7.11 (GHSA-j92g-9f8w-j867)
- `com.fasterxml.jackson.core:jackson-databind` 2.21.4 (GHSA-5gvw-p9qm-jgwh, GHSA-5jmj-h7xm-6q6v, GHSA-mhm7-754m-9p8w)
- `tools.jackson.core:jackson-databind` 3.1.4 (GHSA-5gvw-p9qm-jgwh)

Spring Boot 4.0.8 tilt alle vier naar log4j-api 2.25.5, postgresql 42.7.13, jackson 2.21.5 en 3.1.5. Deze vier doelversies zijn tijdens de audit afzonderlijk aan OSV.dev voorgelegd en komen alle vier leeg terug: de teller gaat van 6 naar 0.

Buiten scope:
- De 4.1.x-lijn (minor-upgrade, ander risicoprofiel).
- Automatische dependency-scanning in CI. Die bestaat vandaag niet en is als losse vervolgkandidaat genoteerd; deze bump zorgt dat die tweede stap groen kan starten in plaats van met zes te onderdrukken meldingen.
- Documentatiewijzigingen. `specs/backend-technical-spec.md:28` noemt 4.0.6 → 4.0.7 uitsluitend als historisch SF-2116-voorbeeld en stelt dat er geen automatische dependency-scan draait; beide blijven na deze bump feitelijk juist. Nul docwerk.
- Nieuwe `<version>`-pins of `<properties>` toevoegen. De pom bevat geen properties die de BOM voor deze vier artefacten overschrijven; de bump is bewust één regel (huisregel `specs/backend-technical-spec.md:28`).

## Acceptance criteria

1. `newsfeedbackend/newsfeedbackend/pom.xml` regel 10 bevat `<version>4.0.8</version>`. `grep -c '4\.0\.7' newsfeedbackend/newsfeedbackend/pom.xml` geeft 0; de diff van de story raakt uitsluitend deze ene regel in de pom (naast de worklog).
2. Er is geen nieuwe `<version>`-pin of versie-property toegevoegd voor log4j, postgresql of een van beide jackson-databind-artefacten.
3. `mvn -B --no-transfer-progress clean test` vanuit `newsfeedbackend/newsfeedbackend` geeft BUILD SUCCESS met `Tests run: 142, Failures: 0, Errors: 0`.
4. `grep -icE 'warning|deprecat|self-attach'` over de volledige buildlog van die run blijft **0** (harde eis sinds SF-2151).
5. `mvn -B --no-transfer-progress dependency:tree` toont na de bump exact: `org.apache.logging.log4j:log4j-api:jar:2.25.5`, `org.postgresql:postgresql:jar:42.7.13`, `tools.jackson.core:jackson-databind:jar:3.1.5`, `com.fasterxml.jackson.core:jackson-databind:jar:2.21.5`.
6. Deze vier regels uit `dependency:tree` staan letterlijk in `docs/stories/worklog/SF-2270-worklog.md`, zodat een volgende audit het resultaat kan naslaan.
7. De CI-stap `backend-maven-verify` (`mvn -B --no-transfer-progress clean verify`, inclusief de e2e-suite op Testcontainers) is groen.

## Aannames

- Refiner-verificatie is daadwerkelijk uitgevoerd in een `/tmp`-kopie op branch `ai/SF-2270`: met 4.0.8 geeft `clean test` BUILD SUCCESS, 142 tests, 0 failures, ruisfilter 0, log 331 regels; `dependency:tree` levert exact de vier versies uit AC5, plus tomcat-embed-core 11.0.24, spring-core 7.0.9 en spring-security-core 7.0.7. De 39 verschoven beheerde versies leveren dus geen enkele testbreuk op — de waarschuwing in de aanleiding blijft staan als kijkrichting, niet als verwacht probleem.
- `which docker` is leeg in de agentcontainer, dus `mvn verify` / de e2e-suite (Testcontainers) is lokaal niet draaibaar. AC7 wordt uitsluitend door CI bevestigd; lokaal is `mvn clean test` (surefire, exclude `**/e2e/**`) de volledige toets.
- Er zijn geen code-, test-, frontend-, spec- of deploywijzigingen nodig. De verwachte diff is: pom-regel 10 plus het worklog-bestand.
- 4.0.8 is resolvebaar vanaf Maven Central in de buildomgeving (aangetoond: de testrun hierboven downloadde en gebruikte 4.0.8).

## Eindsamenvatting

## Eindsamenvatting SF-2270 — Bump spring-boot-starter-parent 4.0.7 → 4.0.8

**Wat is gebouwd**
Eén regelwijziging: `newsfeedbackend/newsfeedbackend/pom.xml` regel 10, `<version>4.0.7</version>` → `<version>4.0.8</version>`. Daarnaast het story-worklog. Verder geen enkele code-, test-, frontend-, spec- of deploywijziging.

**Aanleiding en effect**
OSV.dev meldde 6 advisories over 4 artefacten in de dependency-keten. De nieuwe Spring Boot BOM tilt die alle vier op: log4j-api 2.25.4 → 2.25.5, postgresql 42.7.11 → 42.7.13, `com.fasterxml` jackson-databind 2.21.4 → 2.21.5, `tools.jackson` jackson-databind 3.1.4 → 3.1.5. Teller gaat van 6 naar 0.

**Keuzes**
- Bewust binnen de 4.0.x-lijn gebleven; 4.1.x is een minor-upgrade met ander risicoprofiel en viel buiten scope.
- Geen losse `<version>`-pins of versie-properties toegevoegd voor de vier artefacten — de BOM regelt dat (huisregel `specs/backend-technical-spec.md:28`).
- De vier `dependency:tree`-regels zijn letterlijk in het worklog vastgelegd, zodat een volgende audit ze kan naslaan.

**Wat is getest**
- `mvn clean test`: BUILD SUCCESS, 142 tests, 0 failures/errors; ruisfilter (`warning|deprecat|self-attach`) = 0.
- `mvn clean verify` inclusief de e2e-suite op Testcontainers: BUILD SUCCESS, 142 unit + 77 e2e, 0 failures/errors. De aanname dat e2e lokaal niet draaibaar was klopte niet — de docker-socket bleek bereikbaar, dus AC7 is zowel lokaal als door CI bevestigd.
- Versiebewijs uit de fat jar van deze revisie (`BOOT-INF/lib`): de vier verwachte artefactversies zitten er echt in.
- Live op de preview (`pnf-pr-241`): `GET /api/version` geeft `springVersion: 4.0.8`. Met een wegwerp-account is de hele auth/DB/JSON-keten doorlopen (register, login, settings, rss, requests, rolautorisatie, publieke endpoints) — allemaal zoals verwacht. Account daarna opgeruimd, geen testdata achtergelaten.

Alle 7 acceptatiecriteria zijn aantoonbaar gehaald; geen flakes.

**Bewust niet gedaan**
- Geen automatische dependency-scanning in CI toegevoegd (aparte vervolgkandidaat; deze bump zorgt dat die stap groen kan starten).
- Geen documentatiewijzigingen: `specs/backend-technical-spec.md:28` blijft feitelijk juist.
- **Doorgegeven aan de documentatie-subtaak (SF-2274):** `specs/openapi.yaml:1316` heeft nog `example: 4.0.7` bij `springVersion`, terwijl de live respons 4.0.8 geeft. Puur een spec-voorbeeld, geen functioneel effect.
