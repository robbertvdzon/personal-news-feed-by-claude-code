# SF-1310 - Documentatie: teststrategie-sectie bijwerken (ModuleStructureTest + e2e-suite bestaan al)

## Story

Documentatie: teststrategie-sectie bijwerken (ModuleStructureTest + e2e-suite bestaan al)

<!-- refined-by-factory -->

## Scope

Alleen documentatie bijwerken, geen codewijziging. De volgende bestanden beschrijven een verouderde teststrategie (stellen dat `ModuleStructureTest` en de e2e-suite nog niet bestaan) en moeten worden gecorrigeerd naar de daadwerkelijke situatie:

1. **`specs/backend-technical-spec.md` §7 ("Tests")**
   - Vervang "Huidige testsuite" (die slechts 3 unit-tests noemt) door een correcte opsomming: 6 gerichte unit-tests (`AiJsonTest`, `AiPricingPropertiesTest`, `ApiRequestDtoContractTest`, `VideoAudioDownloaderArgsTest`, `PodcastScriptParserTest`, `RssFetcherImageUrlTest`) plus `ModuleStructureTest` (Spring Modulith-verificatie, lege allowlist, bewaakt module-grenzen als ratchet).
   - Vervang "Beschikbare testtooling" (die Cucumber/WireMock als toekomstig aankondigt) door een beschrijving van de daadwerkelijke e2e-aanpak: `src/test/kotlin/com/vdzon/newsfeedbackend/e2e/` bevat 10 e2e-testklassen (o.a. `RssRefreshE2eTest`, `SettingsE2eTest`, `EventsE2eTest`, `AdminE2eTest`, `AuthE2eTest`, `FeedE2eTest`, `PodcastGenerationE2eTest`, `PodcastIngestE2eTest`, `RequestsE2eTest`, `SharedFeedE2eTest`) plus het harnas `E2eTestBase`/`E2eTestConfig` en fakes `FakeOpenAiChatClient`/`FakeContentServer`. Deze tests starten de volledige Spring-app tegen een echte Postgres via Testcontainers (+ echte Flyway-migraties), waarbij alleen externe diensten (OpenAI, content-fetch) gefaked zijn.
   - Vermeld het testsplit: `mvn test` (surefire) draait alleen de snelle unit-tests + `ModuleStructureTest` (excludeert `**/e2e/**`); `mvn verify` (failsafe) draait daarnaast de e2e-suite (Docker vereist, elke e2e-klasse in eigen JVM-fork).
   - **Bevinding om te melden (niet zelf oplossen):** de Cucumber (`cucumber-spring`, `cucumber-junit-platform-engine`) en WireMock (`wiremock-standalone`) test-dependencies staan nog in `pom.xml` maar zijn ongebruikt — geen `.feature`-bestanden, step-definitions of WireMock-stubs in de repo. De e2e-strategie is in de praktijk vervangen door Testcontainers + handgeschreven Fake-clients. Relativeer/verwijder de Cucumber/WireMock-vooruitzichtstekst in §7 en noem deze dependencies expliciet als (vermoedelijk) legacy/ongebruikt, zonder de dependencies zelf te verwijderen (dat is een codewijziging, buiten scope).

2. **`specs/README.md` regel 48**: verwijder "Cucumber integratie-tests" uit de omschrijving van `backend-technical-spec.md` in de documentatie-tabel.

3. **`docs/factory/technical-spec.md` regel 10**: corrigeer de rij "Modulestructuur | Spring Modulith (verify-test optioneel; nog niet in de repo)" naar een tekst die aangeeft dat de `ModuleStructureTest`-verificatie al actief is (lege allowlist, draait bij elke `mvn test`).

4. **`docs/factory/development.md`** (regels ~21-31 en 60):
   - Corrigeer de claim "de Cucumber/WireMock-integratietests en een `ModuleStructureTest` zijn nog niet aanwezig" — beide bestaan al.
   - Voeg toe dat `mvn verify` de e2e-suite draait (Testcontainers/Docker vereist), zoals ook geconfigureerd in `.factory/verification.yaml` (command `backend-maven-verify` draait `mvn clean verify`).
   - Verduidelijk het onderscheid `mvn test` (snel, geen Docker) vs. `mvn verify` (incl. e2e, Docker vereist).

5. **`docs/factory/agents/developer.md` regel 8**: corrigeer "die test bestaat nog niet in de repo" — de `ModuleStructureTest` bestaat al met een lege allowlist; herformuleer de instructie zodat de developer-agent bij nieuwe module-schendingen de bestaande test raadpleegt/oplost in plaats van een nieuwe test toe te voegen.

Buiten scope: `docs/onboarding-senior-developer.md` (al correct, dient als bron), en elke wijziging aan `pom.xml` of andere broncode (het verwijderen van de ongebruikte Cucumber/WireMock-dependencies is alleen een te melden bevinding, geen actie in deze story).

## Acceptance criteria

- Alle vijf genoemde bestanden beschrijven consistent dat `ModuleStructureTest` en de e2e-testsuite al bestaan, en hoe ze gedraaid worden (`mvn test` vs. `mvn verify`).
- Geen enkele bewering in deze bestanden spreekt de daadwerkelijke code nog tegen (geverifieerd: `ModuleStructureTest.kt` bestaat met lege allowlist; `e2e/`-map bevat 10 testklassen + harnas + fakes; surefire/failsafe-split in `pom.xml` regelt `mvn test` vs. `mvn verify`).
- `specs/backend-technical-spec.md` §7 bevat een expliciete melding dat de Cucumber/WireMock test-dependencies in `pom.xml` ongebruikt lijken (geen feature-files/stubs gevonden).
- Geen enkele broncodewijziging (geen `.kt`, geen `pom.xml`).
- `docs/factory/` blijft compleet en intern consistent na de wijziging.

## Aannames

- "Corrigeer" in punt 3 en 5 betekent: de tekst zodanig herschrijven dat hij de actuele situatie weergeeft; de exacte bewoording is aan de documenter/developer, zolang de strekking klopt.
- Het melden van de ongebruikte Cucumber/WireMock-dependencies is een documentatie-bevinding (tekst in §7), geen verzoek om de dependencies uit `pom.xml` te verwijderen — dat zou een codewijziging zijn en is expliciet buiten scope van deze story.
- Het exacte aantal e2e-testklassen (10, exclusief de 2 support-bestanden `E2eTestBase`/`E2eTestConfig` en 2 fake-clients) mag in de documentatie worden genoemd zonder dat een toekomstige toevoeging van een testklasse deze story ongeldig maakt; formuleer bij voorkeur niet-uitputtend ("o.a.") of verwijs naar de map in plaats van een hard aantal, om onderhoudslast te beperken.

## Eindsamenvatting

## Eindsamenvatting — SF-1310: Documentatie teststrategie-sectie bijwerken

**Wat is gebouwd**
Zuivere documentatie-update (geen codewijziging) om vijf bestanden te corrigeren die nog beschreven dat `ModuleStructureTest` en de e2e-testsuite niet bestonden, terwijl ze in werkelijkheid al aanwezig zijn:

- **`specs/backend-technical-spec.md` §7**: "Huidige testsuite" uitgebreid naar de 6 daadwerkelijke unit-tests + vermelding van `ModuleStructureTest` (Spring Modulith-verificatie, lege allowlist, draait bij elke `mvn test`); nieuwe sectie "E2e-testsuite (`mvn verify`)" met de 10 e2e-testklassen, het harnas (`E2eTestBase`/`E2eTestConfig`) en de fakes (`FakeOpenAiChatClient`, `FakeContentServer`); §3 (moduleregels) eveneens gecorrigeerd.
- **`specs/README.md`**: "Cucumber integratie-tests" in de bestandstabel vervangen door "tests (unit + e2e)".
- **`docs/factory/technical-spec.md`**: rij "Modulestructuur" verwijst nu naar de bestaande `ModuleStructureTest` i.p.v. "nog niet in de repo".
- **`docs/factory/development.md`**: commands- en conventions-secties bijgewerkt met het onderscheid `mvn test` (snel, geen Docker, sluit e2e uit) vs. `mvn verify` (incl. e2e-suite, Docker/Testcontainers vereist, zoals ook in `.factory/verification.yaml` command `backend-maven-verify`); onjuiste claim over `PNF_DATABASE_URL` voor unit-tests verwijderd.
- **`docs/factory/agents/developer.md`**: instructie herschreven zodat de developer-agent bij nieuwe modulegrens-schendingen de bestaande `ModuleStructureTest` raadpleegt/oplost i.p.v. een nieuwe test toe te voegen.

**Belangrijke keuze**
In `specs/backend-technical-spec.md` §7 is een expliciete bevinding opgenomen dat de Cucumber- (`cucumber-spring`, `cucumber-junit-platform-engine`) en WireMock- (`wiremock-standalone`) test-dependencies nog in `pom.xml` staan maar ongebruikt lijken (geen `.feature`-bestanden, step-definitions of stubs gevonden) — de e2e-strategie is in de praktijk vervangen door Testcontainers + handgeschreven Fakes. Dit is bewust alleen gemeld, niet opgelost: het verwijderen van die dependencies is een codewijziging en viel buiten scope.

**Getest**
- Code-inspectie ter verificatie van de claims (`ModuleStructureTest.kt` met lege allowlist; 10 e2e-klassen + harnas + fakes; surefire/failsafe-split in `pom.xml`).
- Als vangnet is `mvn -B --no-transfer-progress clean verify` gedraaid in `newsfeedbackend/newsfeedbackend`: BUILD SUCCESS, 61 tests (unit + e2e via Testcontainers), 0 failures/errors/skipped (Docker was beschikbaar).
- Testsubtaak SF-1312 is afgerond met status test-approved.

**Bewust niet gedaan**
- Geen wijziging aan `pom.xml` of enige `.kt`-broncode (Cucumber/WireMock-dependencies blijven staan, alleen gerapporteerd als bevinding).
- Geen wijziging aan `docs/onboarding-senior-developer.md` (was al correct en diende als bron).
