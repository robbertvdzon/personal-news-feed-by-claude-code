# SF-747 - Worklog

Story-context bij eerste pickup:
Security-pass: kwetsbaarheden vinden en gedrag-neutraal oplossen

Voer een gedrag-neutrale security-pass uit over de codebase en verhelp gevonden kwetsbaarheden zonder functioneel gedrag te wijzigen. Onderzoek per categorie: (1) injectie (SQL/command/template), (2) in-/uitvoervalidatie, (3) onveilige defaults (SecurityConfig, JwtService), (4) gelekte secrets, (5) authenticatie/autorisatie, (6) verouderde/kwetsbare dependencies. Alleen gedrag-neutrale fixes; integratietests/e2e niet aanpassen; lege/kleine diff met onderbouwing is geldig.

## Stappenplan

- [x] read issue and target docs
- [x] security-review per categorie (1-6)
- [x] gedrag-neutrale fixes toepassen waar veilig (geen gevonden → lege diff)
- [x] run relevant tests (`mvn test`)
- [x] update story-log/worklog met bevindingen en beslissingen

## Uitkomst: worklog-only (lege code-diff)

De backend-src én beide frontends (`frontend/lib`, `frontend-reader/lib`) zijn byte-identiek aan de SF-579-baseline (`de75274`): `git diff --stat de75274 HEAD -- newsfeedbackend frontend/lib frontend-reader/lib` is leeg. Deze code is in eerdere security-/consistentie-passes (SF-579/580, SF-663, SF-698) al beoordeeld en geconvergeerd. Onderstaande verificatie is in deze run opnieuw uitgevoerd; er zijn geen nieuwe, veilig (gedrag-neutraal) te verhelpen kwetsbaarheden gevonden. Een lege code-diff is conform AC een geldig eindresultaat.

## Verificatie per categorie

1. **Injectie (SQL/command/template).** Alle SQL gaat via `NamedParameterJdbcTemplate` met benoemde parameters — geen string-concatenatie van externe input. `ProcessBuilder`-aanroepen draaien zonder shell (geen command-injectie mogelijk). De argument-injectie-hardening van `VideoAudioDownloader` (`--` end-of-options vóór de externe `videoUrl`, getest via `VideoAudioDownloaderArgsTest`) is al aanwezig sinds SF-579 en ongewijzigd. Overige `ProcessBuilder`-calls (Mp3Concatenator, AudioTranscoder) gebruiken alleen interne temp-paden.
2. **In-/uitvoervalidatie.** `spring-boot-starter-validation` aanwezig; externe input (RSS/feeds, podcast-URL's, AI-discovery) loopt via bestaande validatie/parsing. Geen nieuwe ongevalideerde endpoint-paden geïntroduceerd (controllers ongewijzigd).
3. **Onveilige defaults (gemeld, bewust gekozen — niet eigenmachtig gewijzigd).**
   - `SecurityConfig`: `CORS addAllowedOriginPattern("*")` zónder `allowCredentials` → veilig want auth via `Authorization: Bearer`-header; inperken breekt web + APK + LAN-clients (gedrag-/integratiewijziging).
   - `CSRF disabled` + `SessionCreationPolicy.STATELESS`: correct patroon voor een token-gebaseerde (JWT) API zonder cookies/sessies.
   - `JwtService`: HS256 met `Keys.hmacShaKeyFor`, `require(bytes.size >= 32)`; `ttl-days` default 30. Bewust gekozen; wijzigen verandert tokengeldigheid = gedragswijziging.
   - `/actuator/**` permitAll + `health.show-details=always`: naar `when-authorized` zetten wijzigt de respons = gedragswijziging.
4. **Gelekte secrets.** Geen hardcoded productie-secrets in code/config. `app.jwt.secret=change-me-...` in `application.properties` is een dev-fallback en wordt in productie overschreven via env `APP_JWT_SECRET` (SealedSecret `JWT_SECRET`) door Spring relaxed binding. API-keys staan als env-placeholders: `app.tavily.api-key=${PNF_TAVILY_API_KEY:}`, `app.openai.api-key=${PNF_OPENAI_API_KEY:}`, `app.elevenlabs.api-key=${PNF_ELEVENLABS_API_KEY:}` — feitelijke waarden in SealedSecret `newsfeed-api-keys`. Geen gevoelige waarden in deze rapportage.
5. **Authenticatie/autorisatie.** Publieke routes (`/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**`, `/actuator/**`) vs. `anyRequest().authenticated()`; admin-only via `/api/admin/**` → `hasRole("ADMIN")`. `JwtAuthFilter` vóór `UsernamePasswordAuthenticationFilter`; BCrypt-verificatie in `AuthServiceImpl`. Configuratie ongewijzigd en consistent met `specs/openapi.yaml` (`security:[]` op de publieke shared-routes).
6. **Dependencies.** `pom.xml`: Spring Boot 4.0.6 (parent BOM beheert de meeste versies), Java 21, Kotlin 2.2.21, jjwt 0.12.6 (laatste 0.12.x), spring-modulith-bom 2.0.0, rome/shedlock/micrometer via vaste versies — allemaal actueel; geen bekende, mechanisch-veilig te bumpen kwetsbaarheid. Android (`frontend/android/app/build.gradle.kts` + `frontend-reader/...`): KGP-`compilerOptions { jvmTarget = JvmTarget.JVM_11 }` al op het in SF-741 gefixte (niet-deprecated) patroon. Geen lokale Flutter/Gradle/SDK in de runner → CI valideert de Flutter/Android-builds.

## Tests

`mvn test` in `newsfeedbackend/newsfeedbackend`: **BUILD SUCCESS — Tests run: 28, Failures: 0, Errors: 0, Skipped: 0** (RssFetcherImageUrlTest 6, AiPricingPropertiesTest 4, ApiRequestDtoContractTest 6, PodcastScriptParserTest 9, VideoAudioDownloaderArgsTest 3). `PNF_DATABASE_URL` niet nodig: de unit-tests raken de DB niet. Geen integratietests/e2e-scenario's aangepast.

## Beslissing

Geen code-wijziging doorgevoerd: er zijn geen kwetsbaarheden gevonden die binnen de genoemde categorieën gedrag-neutraal op te lossen zijn. De vier vaste, bewust gekozen patronen (brede CORS-default, CSRF-disabled/STATELESS bij JWT-API, actuator permitAll/show-details, dev-fallback JWT-secret) zijn gemeld/onderbouwd in plaats van gewijzigd, omdat aanpassing functioneel gedrag of integratie zou breken. Geen risicovolle wijziging die overdracht aan een mens vereist.

## Tester-verificatie (SF-749)

Gedrag-neutrale security-pass geverifieerd via code-inspectie + tests (preview vereist auth, empty code-diff → geen zinvolle browser-gedragstest):
- **Diff-scope:** `git diff main...HEAD --name-only` toont uitsluitend dit worklog-bestand. Code-diff is leeg → geldig eindresultaat conform AC.
- **Baseline-identiek:** `git diff --stat de75274 HEAD -- newsfeedbackend frontend/lib frontend-reader/lib` is leeg → backend-src + beide frontends byte-identiek aan SF-579-baseline. Bevestigt claim.
- **SecurityConfig:** routes bevestigd in `auth/infrastructure/SecurityConfig.kt` — permitAll `/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**`, `/actuator/**`; `/api/admin/**` → `hasRole("ADMIN")`; `anyRequest().authenticated()`; CSRF disabled; STATELESS; `addAllowedOriginPattern("*")` zonder allowCredentials. Komt overeen met rapportage.
- **Secrets:** `application.properties` bevat dev-fallback `app.jwt.secret=change-me-...` en env-placeholders (`${PNF_*_API_KEY:}`); geen productie-secrets in code of in deze rapportage.
- **Tests:** named DB-vrije unit-tests opnieuw gedraaid (`mvn test -Dtest=RssFetcherImageUrlTest,AiPricingPropertiesTest,ApiRequestDtoContractTest,PodcastScriptParserTest,VideoAudioDownloaderArgsTest`) → **28 tests, 0 failures, 0 errors, 0 skipped** (6+4+6+9+3). Komt exact overeen met developer-claim. Destructieve Cucumber/DB-suite niet gedraaid (conform AC: gedeelde DB-risico).
- **Conclusie:** geen gedragswijziging, alle claims reproduceerbaar, geen secrets gelekt. Akkoord.
