# SF-1992 - Worklog

Story-context bij eerste pickup:
SharedCategoryDto + spec- en testaanpassingen voor /api/shared/categories

Backend (newsfeedbackend/newsfeedbackend): voeg shared/api/dto/SharedCategoryDto.kt toe met data class SharedCategoryDto(id: String, name: String, enabled: Boolean) en mapper fun CategorySettings.toSharedDto(): SharedCategoryDto, in dezelfde stijl als SharedFeedItemDto.kt. Geen @JsonProperty-workarounds nodig. KDoc onderbouwt waarom extraInstructions en isSystem ontbreken: het endpoint is permitAll (SecurityConfig.kt:35) en publiek doorgeproxyd, en de enige consument frontend-reader/lib/models.dart:64-76 parseert precies deze drie velden. Pas SharedFeedController.categories() aan zodat die List<SharedCategoryDto> retourneert en de gefilterde (enabled=true) lijst via toSharedDto() mapt; verwijder de import van settings.CategorySettings als die ongebruikt wordt. Breid de klasse-KDoc van SharedFeedController (r13-22) uit zodat de privacy-onderbouwing ook extraInstructions op het categorieen-endpoint dekt. Specs: voeg in specs/openapi.yaml een schema SharedCategory (id, name, enabled, met description in dezelfde stijl) toe direct naast SharedFeedItem, en laat de $ref bij /api/shared/categories daarnaar wijzen in plaats van naar CategorySettings; laat het CategorySettings-schema en de drie $refs vanuit de geauthenticeerde /api/settings/categories-endpoints ongewijzigd. Vul in specs/backend-functional-spec.md (r128) de alinea over de publieke shared-endpoints aan met het eigen SharedCategory-schema en de reden (prive extraInstructions lekt niet). Test: breid in e2e/SharedFeedE2eTest.kt de bestaande case 'alleen enabled categorieen van de shared-user komen terug' uit door bij de seeding een niet-lege extraInstructions mee te geven en te asserteren dat zowel het veld extraInstructions als die waarde niet in de JSON-response voorkomt; het bestaande enabled-filtergedrag blijft geasserteerd. Draai mvn -B --no-transfer-progress clean verify in newsfeedbackend/newsfeedbackend en zorg dat de build slaagt zonder nieuwe [WARNING]-regels; controleer dat ModuleStructureTest groen blijft. Sluit af met een eigen review van de diff tegen de acceptatiecriteria en werk docs/stories/worklog/SF-1992-worklog.md bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw `shared/api/dto/SharedCategoryDto.kt` met `data class SharedCategoryDto(id, name, enabled)`
  en mapper `CategorySettings.toSharedDto()`, in dezelfde stijl als `SharedFeedItemDto.kt`.
  Geen `@JsonProperty`-workarounds nodig: de drie overgebleven velden hebben geen
  getternaam-collisie. De KDoc onderbouwt waarom `extraInstructions`/`isSystem` ontbreken
  (endpoint is `permitAll` en publiek doorgeproxyd; enige consument
  `frontend-reader/lib/models.dart` parseert precies deze drie velden).
- `SharedFeedController.categories()` retourneert nu `List<SharedCategoryDto>` en mapt de op
  `enabled` gefilterde lijst via `toSharedDto()`; de import van `settings.CategorySettings` is
  vervallen. De klasse-KDoc dekt nu ook de privacy-onderbouwing van het categorieën-endpoint.
- `specs/openapi.yaml`: nieuw schema `SharedCategory` (id/name/enabled, met description in
  dezelfde stijl) direct na `SharedFeedItem`; de `$ref` bij `/api/shared/categories` wijst
  daarnaar. `CategorySettings` blijft ongewijzigd bestaan inclusief `extraInstructions`/`isSystem`
  en wordt nog steeds gebruikt door de drie geauthenticeerde `/api/settings/categories`-endpoints
  (r155/169/178). Geverifieerd met een dangling-`$ref`-check (`comm -23` van gebruikte vs.
  gedefinieerde schema-keys): geen dangling refs.
- `specs/backend-functional-spec.md`: de alinea over de publieke shared-endpoints beschrijft nu
  ook het eigen `SharedCategory`-schema en de reden (privé `extraInstructions` lekt niet).
- Tests (zelf geschreven):
  - e2e `SharedFeedE2eTest` — de bestaande case `alleen enabled categorieen van de shared-user
    komen terug` seedt de Kotlin-categorie nu met een niet-lege `extraInstructions` en asserteert
    dat noch de veldnamen `extraInstructions`/`isSystem` noch de waarde in de JSON-response
    voorkomen; het bestaande enabled-filtergedrag blijft geasserteerd.
  - nieuw `shared/api/dto/SharedCategoryDtoTest.kt` (4 unit-tests) op de mapper en de
    serialisatie: exact de drie velden, `enabled = false` blijft behouden, en de privé-tekst lekt niet.

Verificatie:
- `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`:
  BUILD SUCCESS, exit 0, 61 e2e-tests groen, 0 failures/errors, ~3:03 min.
- `mvn -B --no-transfer-progress clean test`: exit 0, 106 unit-tests (was 102) groen,
  `ModuleStructureTest` groen, **0** `[WARNING]`-regels in de output.

Review (SF-1993, reviewer):
- Volledige story-diff (`git diff main...HEAD`) gereviewd: 7 bestanden, geen scope-creep,
  `frontend-reader`, `CategorySettings` en de `permitAll`/nginx-config zijn ongemoeid.
- AC 1-6 geverifieerd op de code: `categories()` retourneert `List<SharedCategoryDto>` en
  mapt ná het `enabled`-filter; de DTO heeft exact `id`/`name`/`enabled`; `openapi.yaml`
  verwijst bij `/api/shared/categories` naar `SharedCategory` terwijl `CategorySettings`
  (:1296) inclusief `extraInstructions`/`isSystem` in gebruik blijft bij de drie
  `/api/settings/categories`-endpoints; `backend-functional-spec.md` r128 aangevuld;
  de e2e-case asserteert veldnaam én waarde als afwezig. `frontend-reader/lib/models.dart`
  (`CategorySettings.fromJson`) parseert inderdaad precies deze drie velden — geen
  contractbreuk voor de reader-app.
- Modulith: `shared.api.dto` importeert `settings.CategorySettings` uit de module-root
  (publieke API van `settings`), zelfde relatie als vóór deze story — geen nieuwe schending.
- Gerichte eigen check: `mvn -B -o test -Dtest=SharedCategoryDtoTest` → 4/4 groen,
  BUILD SUCCESS, geen `[WARNING]`-regels. Het volledige vangnet is niet herdraaid
  (revisiegebonden developer-bewijs staat hierboven).
- Openstaande suggestie (niet blokkerend): in `openapi.yaml` staat `SharedCategory` op
  `required: [id, name]` met `default: true` op `enabled` — overgenomen uit het
  request-georiënteerde `CategorySettings`. Voor dit response-only schema serialiseert
  Kotlin `enabled` altijd, dus `required: [id, name, enabled]` zonder `default` beschrijft
  het contract preciezer. Kan mee in een volgende spec-aanraking.

Test (SF-1994, tester):
- Volledig vangnet: `mvn -B --no-transfer-progress clean verify` in
  `newsfeedbackend/newsfeedbackend` → **BUILD SUCCESS, exit 0**, 106 unit + 61 e2e,
  0 failures / 0 errors / 0 skipped, **0 `[WARNING]`-regels** (AC 7). `ModuleStructureTest`
  en `SharedCategoryDtoTest` (4/4) groen, `SharedFeedE2eTest` 4/4 groen.
- Preview `https://pnf-pr-210.vdzonsoftware.nl` (namespace `pnf-pr-210`): bij aanvang draaide
  nog `sha-e7fdfbb`; halverwege de run rolde ArgoCD `70c77f2` (= HEAD) uit. Alle live-bewijs
  hieronder is daarna herhaald op build `70c77f2` (bevestigd via `/api/version` én het
  scherm "Over deze app": frontend + backend 70c77f2).
- AC 1 live: `curl` zonder token op `GET /api/shared/categories` → HTTP 200, 5 categorieën;
  de union van alle JSON-keys over de hele respons is exact `enabled,id,name` —
  géén `extraInstructions`, géén `isSystem`.
- AC 2 live: endpoint blijft zonder authenticatie bereikbaar (200) en geeft alleen
  `enabled = true` terug; `GET /api/shared/feed` blijft eveneens 200 (geen regressie).
- AC 4 live: `GET /api/settings` mét token geeft nog steeds het volledige `CategorySettings`
  inclusief `extraInstructions` en `isSystem` → het geauthenticeerde contract is ongemoeid.
- AC 4 statisch: `specs/openapi.yaml` geparsed met js-yaml en veld-voor-veld vergeleken met
  `git show main:specs/openapi.yaml`: `CategorySettings` byte-identiek (id/name/enabled/
  extraInstructions/isSystem), enig nieuw schema `SharedCategory` (id/name/enabled), geen
  verwijderde schema's/paths, geen dangling `$ref`s. Na het wegdenken van precies die twee
  bedoelde wijzigingen is de rest van de spec identiek aan main.
- Reader-contract: `frontend-reader/lib/models.dart` `CategorySettings.fromJson` leest exact
  `id`/`name`/`enabled` (met defaults) → geen contractbreuk voor de reader-app.
- UI-smoke (Playwright 420x900, screenshots in `/work/screenshots`): login via de Flutter-UI,
  feed- en Instellingen-scherm renderen normaal, `GET /api/settings` 200. De story raakt geen
  frontend-code; dit is puur een regressie-smoke.
- Inlog-modus: **wegwerp-account** `tester_sf-1992` (fallback), omdat `TESTER_USERNAME`/
  `TESTER_PASSWORD` niet gezet waren. Opgeruimd met `DELETE /api/account/me` → HTTP 200,
  daarna geeft opnieuw inloggen 401. Geen DB-mutaties buiten dit account.
- Niet-blokkerende observatie voor SF-1996 (documentatie): de nieuwe zin in
  `specs/backend-functional-spec.md` r128 spreekt over "de geauthenticeerde
  `/api/settings/categories`-endpoints", maar dat pad bestaat niet — live geeft
  `/api/settings/categories` een 500 ("No static resource"); de echte endpoints zijn
  `GET`/`PUT /api/settings`. Dezelfde foutieve padnaam staat in de refined story zelf.
  Inhoudelijk klopt de bewering (alleen de geauthenticeerde settings-endpoints gebruiken nog
  `CategorySettings`); alleen de padnaam moet `/api/settings` worden.
