# SF-740 - Worklog

Story-context bij eerste pickup:
Gedrag-neutrale code-kwaliteitsrefactor + build-output opschonen

Voer een pure, gedrag-neutrale refactor uit over de drie build-units: Kotlin-backend (newsfeedbackend/newsfeedbackend/, Maven) en de twee Flutter-frontends (frontend/, frontend-reader/, Android Gradle). Verbeter SOLID/leesbaarheid/onderhoudbaarheid (naamgeving, dode code, duplicatie, te lange functies opknippen) zonder publieke contracten of waarneembaar gedrag te wijzigen. Los build-warnings/deprecations op uit de Maven-build en uit beide Android Gradle-builds (frontend/android/build.gradle.kts, frontend-reader/android/build.gradle.kts) zonder nieuwe waarschuwingen te introduceren. Norm = docs/factory/technical-spec.md §Codeconventies + specs/backend-technical-spec.md (laagscheiding API->domain->infrastructure, Spring Modulith-grenzen, logging-niveaus, geen-comments-tenzij-WHY, @param:-use-site-target bij @Value, Jackson-groupId-conventie), specs/openapi.yaml als API-source-of-truth. HARDE REGELS: integratietests/e2e-scenario's (e2e/scenarios/) NIET wijzigen - als een refactor alleen groen wordt door zo'n test aan te passen is dat een gedragswijziging -> ga in error. Bestaande backend-tests (mvn test, vereist PNF_DATABASE_URL) en beide Flutter-test-suites moeten groen blijven. Bekende bewust-afwijkende patronen (Jackson com.fasterxml vs tools.jackson, cross-module domain/infrastructure-imports, domeinmodel-als-HTTP-response) NIET zelf herstellen maar melden. Bij twijfel of gedrag verandert: niet doorvoeren of in error. Kleine/lege diff is een geldige uitkomst. Voer een eigen review-stap uit op de diff.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt (docs/stories/SF-741-code-kwaliteit-build-output.md) zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Backend (Kotlin/Maven): src ongewijzigd sinds SF-579; alle veilig-herstelbare conventies nog conform (grep: @param:Value met 2 gedoc. uitzonderingen, logger 39/39 javaClass, geen inline data-class-DTO in *Controller.kt). `mvn test` = BUILD SUCCESS, 28 tests groen. `mvn clean compile` geeft geen compiler-warnings/deprecations -> Maven-build-output al schoon, geen code-diff.
- Android Gradle build-output opgeschoond: het met KGP 2.1.0 deprecated `kotlinOptions { jvmTarget }`-blok vervangen door de canonieke `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }`-DSL in frontend/android/app/build.gradle.kts en frontend-reader/android/app/build.gradle.kts. Gedrag-neutraal: JVM-target blijft 11. Runner heeft geen flutter/gradle/Android-SDK; warning-reductie wordt door CI gevalideerd.
- Flutter/Dart-src ongewijzigd sinds SF-579; geen veilig-te-verbeteren refactor binnen gedrag-neutrale grenzen. flutter analyze/test door CI.
- Bekende, vooraf-erkende afwijkingen (Jackson-groupId, cross-module domain/infra-imports, domeinmodel-als-HTTP-response, SettingsController zonder class-@RequestMapping) NIET zelf hersteld -> gemeld, geen error-exit (zijn architecturale/risicovolle wijzigingen).
- Eigen review op de diff uitgevoerd: 2 bestanden, identieke gedrag-neutrale deprecation-fix; geen API/endpoint/UI-wijziging; e2e/scenarios/ ongewijzigd. Kleine diff = geldige uitkomst (AC6).
