# SF-343 - nightly: Code-kwaliteit verbeteren (SOLID, build-output)

## Story

nightly: Code-kwaliteit verbeteren (SOLID, build-output)

<!-- refined-by-factory -->

## Scope

Puur kwaliteits-/refactorwerk over de hele codebase, zonder functionele wijzigingen. Het werk valt uiteen in twee sporen:

**1. Code-kwaliteit / SOLID**
- Verbeter leesbaarheid en onderhoudbaarheid: betere naamgeving, verwijderen van dode/ongebruikte code, opheffen van duplicatie, en opsplitsen van te lange functies/klassen.
- Pas waar zinvol SOLID-principes toe (vooral Single Responsibility en het wegwerken van onnodige koppeling), mits dit géén gedrags- of API-wijziging veroorzaakt.
- Respecteer bestaande conventies uit `technical-spec.md`: Spring Modulith-modulegrenzen (communicatie alleen via publieke service-interfaces of Spring events), de lagenstructuur per module, en "geen comments tenzij de WHY niet-vanzelfsprekend is".
- Geen wijzigingen aan het API-contract (`specs/openapi.yaml`), geen Flyway-migratiewijzigingen, geen DB-schema-wijzigingen.

**2. Build-warnings en deprecations**
- Backend: los compiler-/build-warnings en deprecations op uit de Maven-build van `newsfeedbackend/newsfeedbackend/` (`mvn -DskipTests package` / `mvn test`).
- Frontends: los Gradle-warnings/deprecations op in de Android-builds van zowel `frontend/android/` als `frontend-reader/android/`; los daarnaast Dart-analyzer-warnings op die zichtbaar zijn via de bestaande `analysis_options.yaml` voor zover dit zuiver kwaliteits-/deprecatiewerk is.

De omvang mag pragmatisch begrensd worden tot een coherente, veilige set verbeteringen die binnen één run volledig groen op te leveren is; volledigheid is geen vereiste zolang het opgeleverde deel consistent en gedragsneutraal is.

## Acceptance criteria

- Functioneel gedrag is exact gelijk gebleven; geen wijziging aan publieke API, endpoints, DB-schema, of runtime-gedrag.
- `specs/openapi.yaml` en de controllers blijven consistent en ongewijzigd qua contract.
- Spring Modulith-modulegrenzen blijven gerespecteerd; na module-wijzigingen slaagt `ModuleStructureTest` indien aanwezig.
- Alle bestaande tests slagen onveranderd: backend `mvn test` en de Flutter-tests in `frontend/test` en `frontend-reader/test`.
- Integratietests (het vangnet) zijn **niet** gewijzigd. Als een refactor alleen groen te krijgen is door een integratietest aan te passen, wordt die refactor teruggedraaid en gaat de story in error.
- De aangepakte build-warnings/deprecations zijn aantoonbaar verdwenen uit de betreffende build-output (Maven backend en/of Gradle Android-frontends), zonder nieuwe warnings te introduceren.
- De story-log (`docs/stories/<issue-key>-...md`) beschrijft kort welke verbeteringen zijn doorgevoerd en waarom, en bevat geen rauwe JSON-artefacten.

## Aannames

- "Android-frontends" = de Android Gradle-builds onder `frontend/android/` en `frontend-reader/android/`; de Flutter/Dart-laag valt onder hetzelfde kwaliteitsspoor.
- Bij twijfel of een wijziging gedrag verandert, voert de developer die wijziging níet uit (of gaat in error) — gedragsbehoud heeft voorrang op volledigheid.
- Geen nieuwe dependencies, geen versie-upgrades van frameworks/libraries; deprecations worden opgelost met bestaande, gelijkwaardige API's, niet via library-bumps (een bump kan gedrag wijzigen).
- Geen wijziging aan `docs/factory/`-inhoud nodig: deze is reeds volledig ingevuld en git-getrackt (zie agent-tip `sf220-docs-factory-actually-complete`).
- Als de backend-integratietests (Cucumber/WireMock) nog niet in de repo aanwezig zijn, geldt de vangnet-regel onverkort voor wél aanwezige tests; er worden geen integratietests toegevoegd in deze story.

## Eindsamenvatting

## Eindsamenvatting — SF-343: Code-kwaliteit verbeteren (SOLID, build-output)

**Wat is gebouwd**
Puur gedragsneutraal kwaliteitswerk op de backend (`newsfeedbackend/newsfeedbackend/`). Alle 21 Kotlin-compilerwaarschuwingen over de toekomstige annotation-use-site-target van `@Value` ("currently applied to the value parameter only…") zijn weggewerkt door per geval een expliciet `@param:`-target toe te voegen (`@Value(...)` → `@param:Value(...)`). Dit raakt 8 klassen verspreid over de modules: AdminServiceImpl, OpenAiChatHttpClient, JwtService, VideoAudioDownloader, SharedFeedController, TtsClient, WhisperClient en TavilyClient.

**Gemaakte keuzes**
- Spring blijft de waarden injecteren via de constructor-parameter; runtime-gedrag en injectie zijn exact identiek. Alleen de waarschuwing verdwijnt.
- Geen dependency- of versie-bumps, geen wijziging aan API-contract (`specs/openapi.yaml`), controllers, DB-schema, Flyway of Spring Modulith-modulegrenzen.
- Twee gevallen zijn bewust **niet** aangepast omdat `@param:` daar fout/nieuwe warnings zou geven: `PodcastAsyncConfig.kt` (`@Value` op een `@Bean`-methodeparameter → compilefout) en `PodcastTranscriptWorker.kt` (`@Value` op een plain param zonder `val` → nieuwe "redundant target"-warning).

**Wat is getest**
- `mvn -DskipTests clean compile` → BUILD SUCCESS, baseline 21 → 0 warnings, geen nieuwe warnings.
- `mvn test` → `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
- Gedragsneutraliteit aangetoond op preview `pnf-pr-143`: succesvolle boot (bewijst injectie van alle `@param:Value`-velden), auth-flow groen (login → 200 + geldige JWT, token op `/api/feed` → 200, fout wachtwoord → 401, ongetokende call → 403) en UI-login via Flutter geverifieerd. Integratietests niet aangeraakt (niet aanwezig); `ModuleStructureTest` niet aanwezig.

**Bewust niet gedaan**
Het frontend-spoor (Dart-analyzer-warnings + Android-Gradle-deprecations in `frontend/` en `frontend-reader/`) is in deze run niet uitgevoerd. De factory-omgeving heeft geen Flutter/Dart-binary en geen Android-SDK/Gradle-toolchain, waardoor de harde acceptance-eis ("warnings aantoonbaar verdwenen zonder nieuwe te introduceren") niet kon worden aangetoond. Conform "bij twijfel niet doorvoeren" en de pragmatische scope-clausule is dit spoor buiten de run gehouden; de geleverde backend-set is op zichzelf coherent, gedragsneutraal en volledig groen. **Aandachtspunt voor de PO:** de frontend-warnings uit de oorspronkelijke story-scope blijven hiermee open en kunnen in een vervolgstory worden opgepakt zodra een Flutter/Gradle-toolchain beschikbaar is.
