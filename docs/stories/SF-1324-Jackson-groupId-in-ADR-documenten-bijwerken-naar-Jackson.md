# SF-1324 - Jackson-groupId in ADR-documenten bijwerken naar Jackson 3 (tools.jackson)

## Story

Jackson-groupId in ADR-documenten bijwerken naar Jackson 3 (tools.jackson)

<!-- refined-by-factory -->

## Scope
- **specs/backend-technical-spec.md** (rond regel 17 en 18): update de dependency-tabel — vervang de beschrijving van `com.fasterxml.jackson.module:jackson-module-kotlin` + `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` (die laatste is sowieso vervallen; java-time zit ingebouwd in Jackson 3) door: `tools.jackson.module:jackson-module-kotlin` als de daadwerkelijk gebruikte dependency, met de aantekening dat `pom.xml` en vrijwel alle main-code-imports de `tools.jackson`-groupId gebruiken. Voeg een aantekening toe dat `jackson-annotations` (`@JsonProperty` e.d. in DTO's) bewust nog onder het `com.fasterxml.jackson.annotation`-package valt (niet hernoemd in Jackson 3), en dat `jjwt-jackson` (regel 18, ongewijzigd blijven staan) los daarvan intern Jackson 2 gebruikt voor JWT-payloads.
- **docs/factory/technical-spec.md** (§Codeconventies, regel 77): wijzig "Jackson: `pom.xml` en alle imports gebruiken de `com.fasterxml.jackson`-groupId (`jackson-module-kotlin`, `jackson-datatype-jsr310`)" naar de correcte Jackson 3-situatie: `tools.jackson`-groupId voor de kern-mapper/Kotlin-module (`jackson-module-kotlin`), met dezelfde twee aantekeningen (annotations-package ongewijzigd; jjwt-jackson intern Jackson 2).
- Zuivere documentatiewijziging in deze twee bestanden. Geen wijziging aan code, pom.xml, of ander gedrag.

## Acceptance criteria
1. `specs/backend-technical-spec.md` beschrijft Jackson-kern (mapper + Kotlin-module) met groupId `tools.jackson` (niet `com.fasterxml.jackson`), consistent met `pom.xml` regel 109-110 en de imports in main-code.
2. `docs/factory/technical-spec.md` §Codeconventies (regel 77) beschrijft dezelfde `tools.jackson`-groupId voor de kern-mapper/Kotlin-module.
3. Beide documenten vermelden expliciet dat `jackson-annotations`/`@JsonProperty` bewust onder `com.fasterxml.jackson.annotation` blijft (dat package is in Jackson 3 niet hernoemd).
4. Beide documenten vermelden expliciet dat `jjwt-jackson` los daarvan intern Jackson 2 gebruikt voor JWT-payloads, en dat dit geen inconsistentie is maar een bewuste, geïsoleerde afwijking.
5. `jackson-datatype-jsr310` wordt niet langer als losse dependency genoemd in specs/backend-technical-spec.md, aangezien java-time-support in Jackson 3 ingebouwd is (geen aparte module meer, zie `pom.xml` regel 101-103 en `JacksonConfig.kt`-comment).
6. Geen enkel ander bestand (code, pom.xml, tests) wordt gewijzigd; de diff blijft beperkt tot deze twee documentatiebestanden.
7. Geen gedragsverandering; `mvn test` blijft ongewijzigd groen (documentatie-only wijziging raakt geen compileerbare code).

## Aannames
- De regel over `jjwt-jackson` in de dependency-tabel van `specs/backend-technical-spec.md` (regel 18) blijft ongewijzigd staan (groupId `io.jsonwebtoken`, geen Jackson-groupId), enkel de Jackson-kern-rij (regel 17) wordt aangepast.
- De aantekening over `jackson-annotations` wordt toegevoegd als korte prosaregel/voetnoot bij de bestaande tabel/conventie-bullet, niet als aparte nieuwe sectie, om de bestaande documentstructuur te respecteren.
- "docs/factory/" is niet net aangemaakt (bevestigd in eerdere refines, SF-220 e.v.), dus geen extra acceptatiecriterium nodig dat de developer docs/factory aanvult met repo-informatie.

## Eindsamenvatting

Alles is duidelijk genoeg. Ik schrijf nu de eindsamenvatting.

**Eindsamenvatting SF-1324 — Jackson-groupId in ADR-documenten bijwerken naar Jackson 3 (tools.jackson)**

Wat is gedaan: pure documentatiecorrectie in twee bestanden, geen codewijzigingen.

- `specs/backend-technical-spec.md` (§1 Tech Stack & Dependencies): de Jackson-dependencyrij is aangepast van `com.fasterxml.jackson.module:jackson-module-kotlin` + `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` naar `tools.jackson.module:jackson-module-kotlin`. `jackson-datatype-jsr310` is geschrapt als losse dependency omdat java-time-support in Jackson 3 ingebouwd is.
- `docs/factory/technical-spec.md` (§Codeconventies): de Jackson-bullet is analoog bijgewerkt naar de `tools.jackson`-groupId.
- Beide plekken bevatten nu expliciet de twee uitzonderingen: `jackson-annotations`/`@JsonProperty` blijft bewust onder `com.fasterxml.jackson.annotation` (niet hernoemd in Jackson 3), en `jjwt-jackson` gebruikt los daarvan intern nog Jackson 2 voor JWT-payloads — een bewuste, geïsoleerde afwijking, geen inconsistentie.

Onderbouwing/keuzes: de developer heeft niet blind vertrouwd op eerdere (verouderde) agent-tips, maar geverifieerd tegen de actuele code — `pom.xml` (regel 109-110) en `JacksonConfig.kt` bevestigen dat Jackson 3 met de `tools.jackson`-groupId al in gebruik is; een grep over `src/main` bevestigde dat alleen de annotatie-imports nog `com.fasterxml.jackson.annotation` gebruiken.

Getest: `mvn test` — BUILD SUCCESS, 0 failures/errors. Aangezien het een documentatie-only wijziging is, was geen gedragswijziging te verwachten of aangetroffen. `git diff --stat` bevestigt dat de wijziging beperkt is gebleven tot exact deze twee bestanden (2 regels gewijzigd).

Bewust niet gedaan: geen wijzigingen aan code, `pom.xml` of tests — dat viel expliciet buiten scope.
