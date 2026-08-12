# SF-2116 — Bump spring-boot-starter-parent van 4.0.6 naar 4.0.7

Subtaak: SF-2117 (development)

## Stappenplan

- [x] Story, `docs/factory/development.md` en `docs/factory/technical-spec.md` gelezen
- [x] `newsfeedbackend/newsfeedbackend/pom.xml` regel 10: `4.0.6` → `4.0.7` (enige inhoudelijke wijziging)
- [x] `mvn -B --no-transfer-progress clean test` → 116 tests, 0 failures, 0 errors (AC2)
- [x] `mvn -B --no-transfer-progress clean verify` → 116 unit + 71 e2e groen (AC3)
- [x] `mvn dependency:tree` → alle zeven regels uit de story-tabel geverifieerd (AC4)
- [x] `git diff` gecontroleerd: alleen de POM-regel (AC1), geen nieuwe `<version>`-pin (AC5)

## Wat is gedaan en waarom

De security-audit van 2026-08-12 vond 47 openstaande advisories op de geresolveerde
dependency-tree, waarvan er twee daadwerkelijk in deze opstelling landen
(CVE-2026-41838 op `spring-websocket`, CVE-2026-40984 op `micrometer-core`). Beide
worden weggenomen door het dependency management van de nieuwe parent, dus is er
precies één regel gewijzigd; geen enkele expliciete versie-pin is toegevoegd.

Geen code-, test- of docwijziging: het gedrag van de applicatie verandert niet en de
surefire-telling blijft 116. Nieuwe tests zijn hier niet zinvol — een POM-bump heeft
geen eigen gedragsoppervlak; het bewijs is de bestaande suite plus de dependency-tree.

## Geverifieerde versies (`mvn dependency:tree`)

| Component | Verwacht | Waargenomen |
|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.22 | 11.0.22 |
| `org.springframework:spring-core` | 7.0.8 | 7.0.8 |
| `org.springframework:spring-web` | 7.0.8 | 7.0.8 |
| `org.springframework:spring-webmvc` | 7.0.8 | 7.0.8 |
| `org.springframework:spring-websocket` | 7.0.8 | 7.0.8 |
| `org.springframework:spring-expression` | 7.0.8 | 7.0.8 |
| `ch.qos.logback:logback-core` | 1.5.34 | 1.5.34 |
| `io.micrometer:micrometer-core` | 1.16.6 | 1.16.6 |
| `org.postgresql:postgresql` | 42.7.11 | 42.7.11 |
| `tools.jackson.core:jackson-databind` | 3.1.4 | 3.1.4 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.4 | 2.21.4 |

## Testresultaten

- `mvn -B --no-transfer-progress clean test`: BUILD SUCCESS, `Tests run: 116, Failures: 0, Errors: 0`, 0 `[WARNING]`-regels (de warning-vrije baseline uit SF-1945 houdt stand).
- `mvn -B --no-transfer-progress clean verify`: BUILD SUCCESS in ~3:34, 116 unit + 71 e2e (failsafe), 0 failures/errors. De `docker`-CLI ontbreekt in de agent-container, maar `/var/run/docker.sock` is wél aanwezig — Testcontainers draait rechtstreeks tegen de daemon, dus AC3 is hier gewoon lokaal aangetoond.
