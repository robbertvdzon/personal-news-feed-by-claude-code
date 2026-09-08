# SF-1401 - [Audit] @param:Value-conventie herstellen in de drie SSRF-escape-hatch-bestanden

## Story

[Audit] @param:Value-conventie herstellen in de drie SSRF-escape-hatch-bestanden

<!-- refined-by-factory -->

## Samenvatting
In drie recent aan de SSRF-hardening toegevoegde bestanden staat de `@Value`-annotatie nog op de oude manier geschreven in plaats van volgens de afgesproken schrijfwijze. Dit is puur een schoonheidsfout in de code-stijl: de werking van de applicatie verandert hierdoor niet.

## Scope
Wijzig in de volgende drie bestanden de annotatie op de `ssrfAllowLoopback`-constructorparameter van `@Value(...)` naar `@param:Value(...)`, conform docs/factory/technical-spec.md regel 78 en het bestaande patroon in o.a. `auth/infrastructure/JwtService.kt`:

- `newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/settings/domain/SettingsServiceImpl.kt:29`
- `newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/rss/infrastructure/RssFetcher.kt:29`
- `newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/podcast_source/infrastructure/PodcastFeedFetcher.kt:40`

Er is geen ander gebruik van `@Value` in deze drie bestanden. Geen andere bestanden hoeven te wijzigen; geen test- of documentatiewijzigingen nodig (docs/factory/ is al compleet, zie SF-220).

## Acceptance criteria
- In alle drie genoemde bestanden staat `@param:Value("\${app.security.ssrf.allow-loopback:false}")` in plaats van `@Value("\${app.security.ssrf.allow-loopback:false}")` op de `ssrfAllowLoopback`-property.
- Er is geen enkele functionele/gedragsverandering: Spring injecteert de propertywaarde identiek voor en na de wijziging.
- `mvn test` blijft groen (ongewijzigd t.o.v. voor de wijziging).
- Bestaande code-comments boven de constructorparameter (uitleg over de e2e-only escape-hatch) blijven behouden.

## Aannames
- Alleen deze drie regels wijzigen; overige `@Value`-gebruik in de repository (dat al `@param:Value` gebruikt) blijft ongemoeid.
- Dit betreft uitsluitend een Kotlin-annotatie-syntaxwijziging (use-site-target), geen wijziging aan SSRF-validatielogica of defaults.

## Eindsamenvatting

Ik heb genoeg context. De eindsamenvatting:

## Eindsamenvatting SF-1401: @param:Value-conventie herstellen in de SSRF-escape-hatch-bestanden

**Wat is gebouwd**
De `@Value`-annotatie op de `ssrfAllowLoopback`-constructorparameter is in drie bestanden aangepast naar het correcte use-site-target `@param:Value`, conform de afgesproken Kotlin-conventie (docs/factory/technical-spec.md:78) en het bestaande patroon in `JwtService.kt`:
- `SettingsServiceImpl.kt:29`
- `RssFetcher.kt:29`
- `PodcastFeedFetcher.kt:40`

**Gemaakte keuzes**
- Zuivere annotatie-syntaxwijziging, geen gedragswijziging: Spring injecteert de propertywaarde identiek voor en na de wijziging.
- Bestaande code-comments boven de parameters (uitleg over de e2e-only escape-hatch) zijn ongewijzigd behouden.
- Twee andere `@Value`-plekken in de repo (`PodcastAsyncConfig.kt` @Bean-parameter, `PodcastTranscriptWorker.kt` plain ctor-param zonder `val`) zijn bewust buiten scope gelaten — dit zijn bekende, reeds eerder gedocumenteerde uitzonderingen op de conventie.

**Wat is getest**
- Diff geverifieerd: exact de 3 verwachte regels gewijzigd, verder geen wijzigingen buiten de worklog.
- `grep -rn "@Value" src/main/kotlin | grep -v "@param:Value"` bevestigt dat er geen resterend kaal `@Value`-gebruik meer is op de doelparameters (alleen de twee bekende buiten-scope uitzonderingen).
- `mvn test`: BUILD SUCCESS, 71/71 tests groen, 0 failures/errors.

**Bewust niet gedaan**
- Geen browser-/preview-test: dit betreft een pure Kotlin-annotatiewijziging zonder functionele impact, dus dat is niet nodig geacht.
- Geen wijziging aan de twee andere `@Value`-uitzonderingen elders in de repo — expliciet buiten scope van deze story.
