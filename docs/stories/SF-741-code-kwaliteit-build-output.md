# SF-741 — Gedrag-neutrale code-kwaliteitsrefactor + build-output opschonen

Onderdeel van story **SF-740** (nightly: Code-kwaliteit verbeteren — SOLID, build-output).

## Stappenplan
- [x] Issue + factory-docs + backend-spec/openapi lezen
- [x] Backend-conventies verifiëren (grep) + `mvn test` draaien
- [x] Maven build-output op warnings/deprecations controleren
- [x] Android Gradle-builds (beide frontends) op deprecations controleren
- [x] Veilige, gedrag-neutrale build-output-fix doorvoeren
- [x] Eigen review op de diff
- [x] Story-log + worklog bijwerken

## Wat is gedaan en waarom

### Backend (Kotlin, Maven) — al geconvergeerd, geen code-diff
Backend-src is ongewijzigd sinds SF-579 (`git diff --stat de75274 HEAD -- newsfeedbackend/.../src` is leeg). Alle veilig-herstelbare conventies zijn nog steeds conform, geverifieerd via grep:
- `@param:Value`: geen kale `@Value` op een `val`/`var`-property meer; de 2 treffers zijn de gedocumenteerde uitzonderingen (`PodcastAsyncConfig` `@Bean`-methodeparameter en `PodcastTranscriptWorker` plain constructor-param zonder `val`/`var` — `@param:` zou daar een compilefout/redundante-target-warning geven).
- Logger: 39/39 `LoggerFactory.getLogger(javaClass)`.
- Geen inline `data class`-DTO's in `*Controller.kt`.

`mvn test` → **BUILD SUCCESS, 28 tests groen** (RssFetcherImageUrlTest 6, AiPricingPropertiesTest 4, ApiRequestDtoContractTest 6, PodcastScriptParserTest 9, VideoAudioDownloaderArgsTest 3). `PNF_DATABASE_URL` niet nodig: de unit-tests raken de DB niet.

`mvn clean compile` levert **geen** compiler-warnings/deprecations op (de enige WARN-regels zijn functionele test-logging van `AiPricingProperties` over een opzettelijk niet-bestaand model). De Maven-build-output is dus al schoon — niets veilig te verbeteren.

### Android Gradle (beide frontends) — deprecation opgelost
Toolchain (uit `settings.gradle.kts`): Kotlin Gradle Plugin **2.1.0**, AGP 8.9.1, Gradle 8.12.

Met KGP 2.1.0 is het `kotlinOptions { jvmTarget = ... }`-blok in `android {}` **deprecated**. De canonieke, gedrag-neutrale migratie (exact zoals de actuele Flutter-template) is doorgevoerd in `frontend/android/app/build.gradle.kts` én `frontend-reader/android/app/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
...
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
```

Het JVM-target blijft **JVM 11** (identiek aan voorheen); puur een DSL-modernisering, geen build-/gedragswijziging. `compileOptions { source/targetCompatibility = VERSION_11 }` blijft ongewijzigd.

> **Verificatie-noot:** de developer-runner heeft geen flutter/dart/gradle/Android-SDK, dus de Android-builds kunnen hier niet lokaal draaien. Dat de deprecation-warning daadwerkelijk verdwijnt en er geen nieuwe warning bijkomt, wordt door CI gevalideerd. De wijziging zelf is behoudend: hetzelfde JVM-target, de officiële vervang-DSL voor deze KGP-versie.

### Flutter-frontends (Dart) — geen code-diff
`frontend/lib` en `frontend-reader/lib` zijn ongewijzigd sinds SF-579. Er zijn geen Flutter/Dart-binaries in de runner; `flutter analyze`/`flutter test` worden door CI gedraaid. Geen veilig-te-verbeteren Dart-refactor geïdentificeerd binnen de gedrag-neutrale grenzen.

## Bewust niet gewijzigd (bekende, vooraf-erkende afwijkingen → melden, geen error)
Conform de Aannames in de story zijn de volgende patronen **niet** zelf "hersteld" (architecturale/risicovolle wijzigingen, geen mechanische gedrag-neutrale fix):
1. Jackson-groupId `com.fasterxml.jackson` (code/pom is hier consistent; spec is sinds SF-502 aligned — geen daadwerkelijke afwijking meer, maar gemeld voor de volledigheid).
2. Cross-module imports tussen `domain`/`infrastructure` die Modulith-grenzen doorbreken (geen `ApplicationModules.verify()`-test toegevoegd; dat zou de build laten falen = gedragsrisico).
3. Domeinmodellen die direct als HTTP-response worden geserialiseerd.
4. `SettingsController` zonder klasse-`@RequestMapping` (bedient 3 prefixes; een base-path toevoegen zou URL's wijzigen = gedragswijziging).

## Eigen review op de diff
- Diff beperkt tot 2 bestanden (Android Gradle), elk dezelfde gedrag-neutrale deprecation-fix.
- Geen wijziging aan publieke API-contracten, endpoints, responses of UI-gedrag.
- Geen integratietest/e2e-scenario aangeraakt (`e2e/scenarios/` ongewijzigd).
- Backend-build + alle 28 backend-tests groen.
- Kleine diff is een geldige uitkomst (AC6): de codebase was al grotendeels geconvergeerd; de enige veilig-te-verbeteren build-output was de Android `kotlinOptions`-deprecation.
