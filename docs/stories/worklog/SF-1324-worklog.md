# SF-1324 - Worklog

Story-context bij eerste pickup:
Jackson-groupId bijwerken naar tools.jackson in ADR-documenten

Update specs/backend-technical-spec.md (§1 Tech Stack & Dependencies, tabelregel over Jackson): vervang com.fasterxml.jackson.module:jackson-module-kotlin + com.fasterxml.jackson.datatype:jackson-datatype-jsr310 door tools.jackson.module:jackson-module-kotlin (java-time is ingebouwd in Jackson 3, dus jsr310 vervalt als losse vermelding). Vermeld dat pom.xml en vrijwel alle main-code-imports de tools.jackson-groupId gebruiken. Voeg toe dat jackson-annotations (@JsonProperty in DTO's) bewust onder com.fasterxml.jackson.annotation blijft (niet hernoemd in Jackson 3), en dat jjwt-jackson (regel 18, ongewijzigd) los daarvan intern Jackson 2 gebruikt voor JWT-payloads. Update docs/factory/technical-spec.md (§Codeconventies, regel 77) analoog: wijzig de Jackson-bullet naar de tools.jackson-groupId voor de kern-mapper/Kotlin-module, met dezelfde twee aantekeningen. Geen enkel ander bestand (code, pom.xml, tests) wijzigen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Geverifieerd tegen de daadwerkelijke code (niet blind op eerdere agent-tips vertrouwd,
  die stelden dat Jackson nog overal `com.fasterxml` zou zijn): `pom.xml` regel 109-110
  gebruikt al `tools.jackson.module:jackson-module-kotlin` (Jackson 3), met een
  toelichtende comment die exact matcht met de scope van deze story. `JacksonConfig.kt`
  bevestigt Jackson 3 (`tools.jackson.databind.*`) als de auto-geconfigureerde mapper.
  `grep` over `src/main` bevestigt: de meerderheid van imports gebruikt `tools.jackson`;
  de resterende `com.fasterxml.jackson.annotation.JsonProperty`-imports (RssItemDto,
  SharedFeedItemDto, FeedItemDto, SettingsService, RequestService) zijn uitsluitend
  annotatie-gebruik, wat in Jackson 3 bewust niet hernoemd is.
- `specs/backend-technical-spec.md` §1 (dependency-tabel, Jackson-rij): vervangen door
  `tools.jackson.module:jackson-module-kotlin`, met toelichting dat `jackson-datatype-jsr310`
  vervalt (java-time ingebouwd in Jackson 3), dat `jackson-annotations` bewust onder
  `com.fasterxml.jackson.annotation` blijft, en dat `jjwt-jackson` (regel eronder,
  ongewijzigd) los daarvan intern Jackson 2 gebruikt voor JWT-payloads.
- `docs/factory/technical-spec.md` §Codeconventies (Jackson-bullet): analoog bijgewerkt
  naar de `tools.jackson`-groupId met dezelfde twee aantekeningen.
- Geen andere bestanden gewijzigd (geen code, geen pom.xml, geen tests) — diff is beperkt
  tot deze twee documentatiebestanden (`git diff --stat` bevestigt 2 files, 2/2 lines).
- `mvn test` in `newsfeedbackend/newsfeedbackend`: BUILD SUCCESS, 0 failures/errors
  (documentatie-only wijziging, zoals verwacht geen gedragsverandering).
