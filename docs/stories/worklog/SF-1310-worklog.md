# SF-1310 - Worklog

Story-context bij eerste pickup:
Teststrategie-documentatie bijwerken in 5 bestanden

Werk specs/backend-technical-spec.md §7 (secties 'Huidige testsuite' en 'Beschikbare testtooling'), specs/README.md (regel 48), docs/factory/technical-spec.md (regel 10, rij 'Modulestructuur', en de gerelateerde zin in §3 indien tegenstrijdig), docs/factory/development.md (regels ~21-31 en ~60) en docs/factory/agents/developer.md (regel 8) bij zodat ze consistent beschrijven dat ModuleStructureTest.kt al bestaat (lege allowlist) en dat er een e2e-testsuite bestaat onder newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/ (Testcontainers-Postgres, echte Flyway, fakes FakeOpenAiChatClient/FakeContentServer, harnas E2eTestBase/E2eTestConfig). Beschrijf het surefire/failsafe-split: mvn test (snel, excludeert e2e, incl. ModuleStructureTest) vs. mvn verify (draait ook de e2e-suite, Docker vereist, zie .factory/verification.yaml command backend-maven-verify). Formuleer testklasse-aantallen niet-uitputtend ('o.a.') om onderhoudslast te beperken. Voeg in specs/backend-technical-spec.md §7 een expliciete bevinding toe dat de Cucumber (cucumber-spring, cucumber-junit-platform-engine) en WireMock (wiremock-standalone) test-dependencies in pom.xml ongebruikt lijken (geen .feature-bestanden, step-definitions of stubs gevonden) en verwijder/relativeer de tekst die ze als toekomstig aankondigt - zonder pom.xml te wijzigen. Verwijder 'Cucumber integratie-tests' uit de tabelrij in specs/README.md. Geen enkele broncodewijziging (geen .kt, geen pom.xml). Controleer na afloop dat alle vijf bestanden onderling consistent zijn en niets meer de daadwerkelijke code tegenspreekt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Geverifieerd dat `ModuleStructureTest.kt` (lege `KNOWN_VIOLATIONS`-allowlist) en de e2e-suite
  (10 klassen onder `e2e/`, harnas `E2eTestBase`/`E2eTestConfig`, fakes `FakeOpenAiChatClient`/
  `FakeContentServer`) al bestaan, en dat `pom.xml` het surefire/failsafe-split heeft zoals
  beschreven (surefire sluit `**/e2e/**` uit, failsafe pakt `**/e2e/*E2eTest.*` op met
  `reuseForks=false`).
- `specs/backend-technical-spec.md` §7 herschreven: "Huidige testsuite" nu met de 6 unit-tests
  (incl. `ModuleStructureTest`-vermelding), nieuwe sectie "E2e-testsuite (`mvn verify`)" met de
  10 e2e-klassen + harnas/fakes, en nieuwe sectie "Ongebruikte testtooling (bevinding)" die de
  Cucumber/WireMock-dependencies als vermoedelijk legacy/ongebruikt meldt (zonder `pom.xml` te
  wijzigen). §3 (Moduleregels) ook gecorrigeerd: verwijst niet meer naar een ontbrekende
  verify-test.
- `specs/README.md` regel 48: "Cucumber integratie-tests" vervangen door "tests (unit + e2e)".
- `docs/factory/technical-spec.md` regel 10: rij "Modulestructuur" verwijst nu naar de bestaande
  `ModuleStructureTest` i.p.v. "nog niet in de repo".
- `docs/factory/development.md`: Commands-sectie en Conventions-sectie (Spring Modulith-punt) en
  de losse "Tests draaien"-sectie onderaan bijgewerkt met het `mvn test` vs. `mvn verify`-onderscheid
  (incl. verwijzing naar `.factory/verification.yaml` command `backend-maven-verify`); de oude,
  onjuiste claim dat unit-tests `PNF_DATABASE_URL` nodig hebben is verwijderd (alleen de e2e-suite
  raakt een echte database, via Testcontainers).
- `docs/factory/agents/developer.md` regel 8: herformuleerd zodat de developer-agent bij nieuwe
  modulegrens-schendingen `ModuleStructureTest` raadpleegt/oplost i.p.v. een nieuwe test toe te
  voegen.
- Alleen documentatie gewijzigd, geen `.kt`- of `pom.xml`-wijziging.
- Vangnet gedraaid: `mvn -B --no-transfer-progress clean verify` in
  `newsfeedbackend/newsfeedbackend` — BUILD SUCCESS, 61 tests (unit + e2e via Testcontainers),
  0 failures, 0 errors, 0 skipped. Docker was in deze run beschikbaar.
