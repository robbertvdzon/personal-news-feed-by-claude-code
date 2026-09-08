# SF-2116 - [Audit] Bump spring-boot-starter-parent van 4.0.6 naar 4.0.7

## Story

[Audit] Bump spring-boot-starter-parent van 4.0.6 naar 4.0.7

<!-- refined-by-factory -->

## Aanleiding

De security-audit van 2026-08-12 heeft de volledige dependency-tree van de backend geresolved en tegen de OSV.dev-kwetsbaarhedendatabase gehouden. Van de 189 geresolveerde artefacten hebben er 13 een openstaand advisory, samen 47 stuks, allemaal met een exacte versiematch.

De meeste daarvan zijn in deze opstelling niet misbruikbaar (geen HTTP/2, geen DIGEST-auth, geen `activateDefaultTyping`, geen `UriComponentsBuilder`, geen SocketAppender). Twee landen wel:

- **CVE-2026-41838** — voorspelbare WebSocket-sessie-id's in `spring-websocket` 7.0.7. De advisory noemt expliciet dat dit uitbuitbaar wordt "in combination with inadequate authorization rules", en `/ws/**` staat in deze applicatie op `permitAll` zonder enige check.
- **CVE-2026-40984** — DoS via de HTTP-server-instrumentatie van `micrometer-core` 1.16.5 (hoog). De Prometheus-registry is in gebruik en `/actuator/prometheus` is publiek bereikbaar.

## Scope

Eén regelwijziging in `newsfeedbackend/newsfeedbackend/pom.xml`, regel 10: de versie van `spring-boot-starter-parent` gaat van `4.0.6` naar `4.0.7`.

Verder niets. Er hoeft geen enkele expliciete `<version>` in de POM mee te veranderen: alle betrokken componenten worden door de parent gemanaged. Er is geen code-, test- of docwijziging nodig.

Deze bump verhoogt via dependency management (geverifieerd met `mvn dependency:tree` op een kopie van de backend):

| Component | Van | Naar |
|---|---|---|
| `tomcat-embed-core` | 11.0.21 | 11.0.22 |
| `spring-core` / `-web` / `-webmvc` / `-websocket` / `-expression` | 7.0.7 | 7.0.8 |
| `logback-core` | 1.5.32 | 1.5.34 |
| `micrometer-core` | 1.16.5 | 1.16.6 |
| `postgresql` (pgjdbc) | 42.7.10 | 42.7.11 |
| `tools.jackson.core:jackson-databind` | 3.1.2 | 3.1.4 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.2 | 2.21.4 |

Daarmee gaan 47 advisories terug naar 5, en beide punten die in deze opstelling daadwerkelijk landen zijn weg.

## Acceptance criteria

1. `newsfeedbackend/newsfeedbackend/pom.xml` regel 10 staat op `4.0.7`; er zijn geen andere wijzigingen in de POM en geen wijzigingen elders in de repo (afgezien van de verplichte worklog-/docsstappen van de factory).
2. `mvn -B clean test` in `newsfeedbackend/newsfeedbackend` slaagt met exact 116 tests, 0 failures, 0 errors.
3. `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend` slaagt, inclusief de e2e-suite (failsafe, `**/e2e/*E2eTest.*`). Deze stap vereist Docker/Testcontainers; als die in de uitvoeromgeving van de developer niet beschikbaar is, wordt dat expliciet gemeld en leunt de story op de factory-verificatiepipeline (`.factory/verification.yaml`, `backend-maven-verify`).
4. `mvn dependency:tree` laat de versies uit de tabel hierboven zien; controleer in elk geval `tomcat-embed-core` 11.0.22 en `spring-websocket` 7.0.8.
5. Er wordt geen enkele nieuwe expliciete `<version>`-pin toegevoegd om dit te bereiken.

## Aannames

- De bump is vóór refinement al reproduceerbaar uitgeprobeerd in een kopie buiten de repo: `mvn -B clean test` gaf 116 tests, 0 failures, BUILD SUCCESS, en `mvn dependency:tree` bevestigde alle zeven regels uit de tabel exact. Er was geen compile- of testaanpassing nodig. De e2e-suite kon daar niet draaien (geen Docker in die omgeving); die verificatie hoort bij AC3.
- Het bestaande gedrag van de applicatie verandert niet; er zijn geen nieuwe of gewijzigde tests nodig. De testtelling van 116 is de surefire-telling (e2e-klassen worden daar geëxcludeerd) en blijft ongewijzigd.
- Er is geen frontend-, OpenAPI- of spec-raakvlak: geen enkel document of Flutter-scherm noemt de versienummers van deze artefacten.

## Buiten scope

- Het automatiseren van dependency-scanning (Dependabot/Renovate/OSV in CI). Logische opvolger, maar hoort niet in deze story.
- De ontbrekende autorisatie op `/ws/**` zelf. Deze bump neemt CVE-2026-41838 weg en verzacht dat punt daarmee gedeeltelijk, maar lost het niet op.
- Vier Jackson-`@JsonView`-bypasses die pas in 3.1.5/2.21.5 gefixt zijn — `@JsonView` wordt in deze codebase niet gebruikt.
- De pgjdbc channel-binding-downgrade CVE-2026-54291, die 42.7.12 vereist en alleen relevant is bij `channelBinding=require`.

## Eindsamenvatting

Alles gelezen: `.task.md`, het worklog van SF-2116 (developer/reviewer/tester) en de daadwerkelijke diff (`git diff main...HEAD`).

## Eindsamenvatting SF-2116 — Bump spring-boot-starter-parent 4.0.6 → 4.0.7

**Wat is gebouwd**
Eén regelwijziging: `newsfeedbackend/newsfeedbackend/pom.xml` regel 10, `spring-boot-starter-parent` van `4.0.6` naar `4.0.7`. De story-diff bestaat uit 3 bestanden: die POM-regel plus het story-log en het worklog. Geen code-, test-, spec- of frontendwijziging.

**Keuzes**
- Geen enkele expliciete `<version>`-pin toegevoegd (AC5): alle betrokken componenten worden door de parent gemanaged. Aantal `<version>`-elementen in de POM is 20 op zowel main als HEAD.
- Via dependency management schuiven mee: tomcat-embed-core 11.0.21→11.0.22, spring-core/-web/-webmvc/-websocket/-expression 7.0.7→7.0.8, logback-core 1.5.32→1.5.34, micrometer-core 1.16.5→1.16.6, postgresql 42.7.10→42.7.11, jackson-databind 3.1.2→3.1.4 en 2.21.2→2.21.4.
- Security-effect: 47 openstaande advisories terug naar 5; de twee die in deze opstelling daadwerkelijk uitbuitbaar waren (CVE-2026-41838 WebSocket-sessie-id's, CVE-2026-40984 micrometer-DoS) zijn weg.

**Wat is getest**
- `mvn -B clean verify` door zowel developer als tester: BUILD SUCCESS, 116 unit-tests en 71 e2e-tests, 0 failures/errors, 0 warnings. De `docker`-CLI ontbreekt in de agent-containers, maar `/var/run/docker.sock` is aanwezig, dus Testcontainers draaide gewoon — AC3 is lokaal aangetoond en hoefde niet aan de factory-pipeline te worden gelaten.
- `mvn dependency:tree` bevestigde alle versies uit de story-tabel exact; de reviewer verifieerde dit onafhankelijk via `BOOT-INF/lib` in de gebouwde jar, wat tevens aantoont dat het groene bewijs bij déze revisie hoort.
- Preview-verificatie (`pnf-pr-223`): `/api/version` toont `springVersion: 4.0.7`; gedeelde feed (543 items), `/actuator/prometheus` (184 metricregels, micrometer-instrumentatie intact), `wss://.../ws/requests` (verbinding + serverVersion-frame), en geauthenticeerde lees/schrijfpaden alle groen. Flutter-UI via Playwright: registratie, login en feedscherm zonder console-errors of HTTP≥400. Testaccount opgeruimd (`DELETE /api/account/me` → 200).
- Geen flakes; geen enkele test hoefde te worden herdraaid.

**Bewust niet gedaan**
- Autorisatie op `/ws/**` blijft `permitAll` — de bump verzacht dit punt maar lost het niet op (buiten scope).
- Geen automatisering van dependency-scanning (Dependabot/Renovate/OSV in CI).
- pgjdbc CVE-2026-54291 (vereist 42.7.12, alleen relevant bij `channelBinding=require`) en vier Jackson-`@JsonView`-bypasses (`@JsonView` wordt hier niet gebruikt) zijn bewust blijven staan.

**Openstaand punt voor de documentatie-subtaak (SF-2120)**
`specs/openapi.yaml:1283` heeft nog `example: 4.0.6` bij `springVersion`. Functioneel klopt alles (de waarde komt runtime uit `SpringBootVersion.getVersion()`), alleen het voorbeeld in de spec is verouderd.
