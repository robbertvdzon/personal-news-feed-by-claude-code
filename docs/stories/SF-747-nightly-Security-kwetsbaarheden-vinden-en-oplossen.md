# SF-747 - nightly: Security: kwetsbaarheden vinden en oplossen

## Story

nightly: Security: kwetsbaarheden vinden en oplossen

<!-- refined-by-factory -->

## Scope
Voer een gedrag-neutrale security-pass uit over de codebase en verhelp gevonden kwetsbaarheden zonder het functionele gedrag te wijzigen.

Onderzoeksgebieden (bekende patronen):
- **Injectie**: SQL/command/template-injectie in backend (Kotlin/Spring, `newsfeedbackend/newsfeedbackend/`) en frontends (`frontend/`, `frontend-reader/`).
- **In-/uitvoervalidatie**: ontbrekende of zwakke validatie op API-endpoints en externe input (RSS/feeds, podcast-URL's, AI-calls).
- **Onveilige defaults**: o.a. `SecurityConfig` (CORS `allowedOriginPattern '*'`, CSRF disabled, STATELESS), JWT-config (`JwtService`, HS256, ttl-days default 30, secret-eis ≥32 bytes).
- **Gelekte secrets**: hardcoded keys/tokens/wachtwoorden in code, config of docs (secrets horen in SealedSecret `newsfeed-api-keys`).
- **Ontbrekende authenticatie/autorisatie**: publieke routes (`/api/auth/**`, `/api/version`, `/api/shared/**`, `/ws/**`, `/actuator/**`) vs. beveiligde routes; admin-only `/api/admin/**` (`hasRole ADMIN`); correcte werking van `JwtAuthFilter` en BCrypt in `AuthServiceImpl`.
- **Verouderde/kwetsbare dependencies**: Maven (`pom.xml`) en de twee Flutter/Android-builds.

Pas de code aan om gevonden problemen op te lossen, mits het gedrag exact gelijk blijft. Een kleine of lege diff is een geldige uitkomst wanneer er geen veilig op te lossen kwetsbaarheden worden gevonden.

## Acceptance criteria
- Gevonden security-problemen binnen de genoemde categorieën zijn opgelost met behoud van exact hetzelfde functionele gedrag.
- Alle bestaande tests blijven slagen; `mvn test` is groen (vereist `PNF_DATABASE_URL`).
- Integratietests en e2e-scenario's (`e2e/scenarios/`) zijn **niet** aangepast. Als een fix alleen groen te krijgen is door een integratietest te wijzigen, wordt dat als gedragswijziging beschouwd → de agent gaat in error in plaats van de test aan te passen.
- Grote of risicovolle wijzigingen die gedrag zouden veranderen, worden niet zelf doorgevoerd maar met een duidelijke beschrijving als error/melding overgedragen aan een mens.
- Geen secrets in de output of in nieuwe code/config; bevindingen worden zonder gevoelige waarden gerapporteerd.
- Een lege of kleine diff (met onderbouwing in het worklog) is een geldig eindresultaat.

## Aannames
- Dit is een gedrag-neutrale nightly security-pass; "functioneel gedrag exact gelijk" is de harde randvoorwaarde, net als bij SF-579/SF-663.
- Norm/context: `docs/factory/technical-spec.md`, `specs/backend-technical-spec.md` en `specs/openapi.yaml`; auth-module onder `newsfeedbackend/.../auth/`.
- Bekende, bewust gekozen of niet veilig-te-wijzigen patronen (bijv. brede CORS-default, STATELESS/CSRF-disabled bij JWT-API) worden gemeld/onderbouwd in plaats van eigenmachtig veranderd, omdat aanpassing gedrag of integratie kan breken.
- `docs/factory/` is compleet (SF-220) → geen apart docs-aanvulling-acceptatiecriterium nodig.
- Het worklog (`docs/stories/worklog/SF-747-worklog.md`) legt de bevindingen en beslissingen vast, ook bij een lege diff.

## Eindsamenvatting

## Eindsamenvatting — SF-747: Security: kwetsbaarheden vinden en oplossen

**Type:** nightly, gedrag-neutrale security-pass over de PNF-codebase.

### Uitkomst
Geldig eindresultaat met **lege code-diff** (alleen worklog toegevoegd). Er zijn geen kwetsbaarheden gevonden die binnen de gevraagde categorieën gedrag-neutraal op te lossen zijn. Backend-src en beide frontends (`frontend/lib`, `frontend-reader/lib`) zijn byte-identiek aan de SF-579-baseline; deze code is in eerdere passes (SF-579/580, SF-663, SF-698) al beoordeeld en geconvergeerd. De verificatie is in deze run opnieuw volledig uitgevoerd.

### Wat is onderzocht (6 categorieën)
1. **Injectie** — Alle SQL via `NamedParameterJdbcTemplate` met benoemde parameters (geen concatenatie). `ProcessBuilder` draait zonder shell; `VideoAudioDownloader` gebruikt al `--` end-of-options vóór externe URL (sinds SF-579). Geen injectiepad gevonden.
2. **In-/uitvoervalidatie** — `spring-boot-starter-validation` aanwezig; externe input (RSS, podcast-URL's, AI-discovery) loopt via bestaande validatie. Controllers ongewijzigd.
3. **Onveilige defaults** — bewust gekozen, **gemeld i.p.v. gewijzigd** (zie keuzes hieronder).
4. **Gelekte secrets** — geen hardcoded productie-secrets. JWT-secret en API-keys zijn env-placeholders die in productie via SealedSecrets worden ingevuld. Geen gevoelige waarden in de rapportage.
5. **Authenticatie/autorisatie** — publieke vs. beveiligde routes correct; admin-only via `hasRole("ADMIN")`; `JwtAuthFilter` + BCrypt consistent met `openapi.yaml`.
6. **Dependencies** — Spring Boot 4.0.6, Java 21, Kotlin 2.2.21, jjwt 0.12.6 — actueel; geen mechanisch-veilig te bumpen kwetsbaarheid. Android-builds op niet-deprecated patroon (SF-741).

### Gemaakte keuzes (bewust niet gewijzigd, met onderbouwing)
- **Brede CORS-default** (`addAllowedOriginPattern("*")` zonder `allowCredentials`): veilig bij Bearer-auth; inperken breekt web + APK + LAN-clients.
- **CSRF disabled + STATELESS**: correct patroon voor token-gebaseerde JWT-API zonder cookies.
- **JWT HS256, ttl 30 dagen, secret ≥32 bytes**: wijzigen verandert tokengeldigheid = gedragswijziging.
- **`/actuator/**` permitAll + `health show-details=always`**: aanpassen wijzigt de respons = gedragswijziging.

Geen risicovolle wijziging die overdracht aan een mens vereist.

### Tests
`mvn test` (backend): **BUILD SUCCESS — 28 tests, 0 failures, 0 errors, 0 skipped** (RssFetcherImageUrlTest 6, AiPricingPropertiesTest 4, ApiRequestDtoContractTest 6, PodcastScriptParserTest 9, VideoAudioDownloaderArgsTest 3). Tester (SF-749) heeft de DB-vrije unit-tests onafhankelijk gereproduceerd met identiek resultaat en alle claims (diff-scope, baseline-identiek, SecurityConfig-routes, secrets) bevestigd. **Akkoord.**

### Bewust niet gedaan
- Geen code- of configwijziging (geen veilige gedrag-neutrale fix beschikbaar).
- Integratietests/e2e-scenario's niet gedraaid/aangepast (gedeelde-DB-risico, conform AC).
- Flutter/Android-builds niet lokaal gevalideerd (geen SDK in runner) → CI valideert dit.
