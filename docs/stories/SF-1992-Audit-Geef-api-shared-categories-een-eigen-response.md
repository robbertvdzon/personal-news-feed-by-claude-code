# SF-1992 - [Audit] Geef /api/shared/categories een eigen response-DTO zonder extraInstructions

## Story

[Audit] Geef /api/shared/categories een eigen response-DTO zonder extraInstructions

<!-- refined-by-factory -->

## Samenvatting

Het publieke overzicht met categorieën is voor iedereen op internet op te vragen, zonder inloggen. Dat is de bedoeling: de reader-app heeft die namen nodig voor z'n tabjes.

Maar we sturen op dit moment de volledige categorie-instelling mee, inclusief de vrije tekst waarmee de gebruiker het taalmodel bijstuurt. Die tekst is privé en hoort niet op straat te liggen.

We gaan daarom alleen nog de drie velden meesturen die de reader-app echt gebruikt: het id, de naam en of de categorie aanstaat. Voor gebruikers verandert er niets zichtbaars; de app blijft precies hetzelfde werken.

Dezelfde aanpak is eerder al toegepast op de gedeelde nieuwsfeed. Daarmee is het laatste publieke endpoint dat te veel prijsgaf ook afgedekt.

## Scope

**Backend (`newsfeedbackend/newsfeedbackend`)**

- Nieuw `shared/api/dto/SharedCategoryDto.kt`, in dezelfde stijl als het bestaande `SharedFeedItemDto.kt`:
  - `data class SharedCategoryDto(val id: String, val name: String, val enabled: Boolean)` — uitsluitend deze drie velden.
  - Mapper `fun CategorySettings.toSharedDto(): SharedCategoryDto` in hetzelfde bestand.
  - KDoc die expliciet onderbouwt waaróm `extraInstructions` (en `isSystem`) ontbreken: het endpoint is `permitAll` en publiek doorgeproxyd, en de enige consument (`frontend-reader/lib/models.dart`, `CategorySettings.fromJson`) parseert precies deze drie velden.
- `shared/api/SharedFeedController.kt`: `categories()` retourneert `List<SharedCategoryDto>` en mapt de gefilterde lijst via `toSharedDto()`. De import van `settings.CategorySettings` vervalt als die verder ongebruikt wordt.
- De klasse-KDoc van `SharedFeedController` (r13-22) uitbreiden zodat de privacy-onderbouwing niet alleen over de feed-vlaggen gaat maar ook over `extraInstructions` op het categorieën-endpoint.

**Spec (`specs/`)**

- `specs/openapi.yaml`: nieuw schema `SharedCategory` (id, name, enabled) bij de andere Shared-schema's, direct naast `SharedFeedItem` (:1573), met een `description` in dezelfde stijl. Het `$ref` bij `/api/shared/categories` (:1008) wijst naar `SharedCategory` in plaats van `CategorySettings`.
- `CategorySettings` blijft ongewijzigd bestaan voor de geauthenticeerde `/api/settings/categories`-endpoints (:155, :169, :178).
- `specs/backend-functional-spec.md` r128: de bestaande alinea over de publieke shared-endpoints aanvullen, zodat naast `SharedFeedItem` ook het eigen `SharedCategory`-schema wordt beschreven met de reden (privé `extraInstructions` lekt niet).

**Test**

- `e2e/SharedFeedE2eTest.kt`, test `alleen enabled categorieen van de shared-user komen terug` (r98-114): seedt al een categorie; die seeding krijgt een niet-lege `extraInstructions`, en de test asserteert dat dat veld (en de waarde ervan) niet in de response voorkomt.

**Buiten scope**

- `frontend-reader` — parseert al precies de drie overblijvende velden en hoeft niet te wijzigen.
- De geauthenticeerde `/api/settings/categories`-endpoints en `CategorySettings` zelf.
- De `permitAll`-configuratie in `SecurityConfig.kt` en de nginx-proxyconfigs; het endpoint blijft bewust publiek.

## Acceptance criteria

1. `GET /api/shared/categories` retourneert per categorie uitsluitend `id`, `name` en `enabled`; `extraInstructions` en `isSystem` komen niet in de JSON voor.
2. Het endpoint blijft zonder authenticatie bereikbaar en blijft alleen categorieën met `enabled = true` teruggeven (bestaand gedrag ongewijzigd).
3. Er bestaat `shared/api/dto/SharedCategoryDto.kt` met de DTO plus een `CategorySettings.toSharedDto()`-mapper, en `SharedFeedController.categories()` retourneert `List<SharedCategoryDto>`.
4. `specs/openapi.yaml` definieert een `SharedCategory`-schema waarnaar `/api/shared/categories` verwijst; het `CategorySettings`-schema bevat nog steeds `extraInstructions`/`isSystem` en wordt nog steeds door de `/api/settings/categories`-endpoints gebruikt.
5. `specs/backend-functional-spec.md` beschrijft in de alinea over de publieke shared-endpoints dat óók de categorieën een eigen, uitgeklede responsevorm hebben en waarom.
6. De bestaande e2e-categorie-case in `SharedFeedE2eTest.kt` asserteert expliciet dat `extraInstructions` niet in de response voorkomt.
7. `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend` slaagt zonder nieuwe compile-warnings.

## Aannames

- `enabled` blijft in de DTO staan, ook al is de waarde in deze response altijd `true` door de filter: de reader-app leest het veld (`models.dart:64-76`) en weglaten zou het contract onnodig veranderen.
- `isSystem` valt af. De reader parseert het niet en het zegt iets over de interne inrichting van de bron-gebruiker; het minimale contract van de story (id/name/enabled) is leidend.
- De DTO gebruikt geen `@JsonProperty`-workarounds: die zijn in `SharedFeedItemDto`/`CategorySettings` alleen nodig voor `summary`/`isSummary`/`isSystem`-achtige getternamen, en de drie overblijvende velden hebben dat probleem niet.
- Over het "tweeling-patroon": beide publieke `/api/shared/*`-endpoints hebben na deze story een eigen shared-DTO (feed via SF-1884, categorieën hier). Er blijft geen derde helft open onder `/api/shared/**`; er zijn geen andere endpoints op `permitAll` die een domeinobject teruggeven.
- Bestaande vulnerabiliteit is niet retro-actief te dichten: er wordt geen aanname gedaan dat eerder gelekte `extraInstructions` ergens ingetrokken moet worden — dit is puur een contractwijziging vooruit.

## Eindsamenvatting

Alle context gelezen: `.task.md`, worklog, en de volledige story-diff (7 bestanden).

---

# SF-1992 — Eigen response-DTO voor `/api/shared/categories`

## Wat is gebouwd

Het publieke, niet-geauthenticeerde endpoint `GET /api/shared/categories` gaf tot nu toe het volledige domeinobject `CategorySettings` terug, inclusief de privé `extraInstructions` (de vrije tekst waarmee de gebruiker het taalmodel bijstuurt) en het interne `isSystem`. Die informatie lag daarmee voor iedereen op internet open. Dat is nu dichtgezet:

- **Nieuw `shared/api/dto/SharedCategoryDto.kt`** — `data class SharedCategoryDto(id, name, enabled)` plus mapper `CategorySettings.toSharedDto()`, in dezelfde stijl als het bestaande `SharedFeedItemDto`. De KDoc legt expliciet vast *waarom* `extraInstructions` en `isSystem` ontbreken (endpoint is `permitAll` en publiek doorgeproxyd; de enige consument, de reader-app, parseert precies deze drie velden).
- **`SharedFeedController.categories()`** retourneert nu `List<SharedCategoryDto>` en mapt ná het bestaande `enabled`-filter. De klasse-KDoc dekt nu ook de privacy-onderbouwing van het categorieën-endpoint.
- **`specs/openapi.yaml`** — nieuw schema `SharedCategory` (id/name/enabled) naast `SharedFeedItem`; de `$ref` bij `/api/shared/categories` wijst daarnaar. `CategorySettings` blijft ongewijzigd bestaan voor de geauthenticeerde settings-endpoints.
- **`specs/backend-functional-spec.md`** — de alinea over de publieke shared-endpoints beschrijft nu ook het `SharedCategory`-schema en de reden.

Hiermee hebben beide publieke `/api/shared/*`-endpoints een eigen uitgeklede DTO (feed via SF-1884, categorieën hier); er staat geen ander `permitAll`-endpoint meer dat een domeinobject teruggeeft.

## Gemaakte keuzes

- **`enabled` blijft in de DTO**, hoewel de waarde door het filter altijd `true` is — de reader-app leest het veld, weglaten zou het contract onnodig breken.
- **`isSystem` valt af**: niemand parseert het en het beschrijft de interne inrichting van de bron-gebruiker.
- **Geen `@JsonProperty`-workarounds**: die zijn elders alleen nodig voor `isX`-getternamen, wat bij deze drie velden niet speelt.
- **Frontend-reader bewust niet aangepast** — `CategorySettings.fromJson` leest al exact deze drie velden, dus geen contractbreuk.

## Wat is getest

- `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend`: **BUILD SUCCESS**, 106 unit- + 61 e2e-tests groen, 0 failures/errors, **0 `[WARNING]`-regels** (AC 7). Door developer én tester onafhankelijk gedraaid.
- **Nieuw `SharedCategoryDtoTest.kt`** (4 unit-tests): mapper en JSON-serialisatie — exact drie velden, `enabled = false` blijft behouden, privé-tekst lekt niet.
- **Bestaande e2e-case uitgebreid**: de categorie wordt nu geseed mét een niet-lege `extraInstructions`, en de test asserteert dat zowel de veldnamen als de waarde niet in de JSON voorkomen.
- **Live op preview `pnf-pr-210`** (geverifieerd op build `70c77f2` = branch-HEAD): zonder token HTTP 200, 5 categorieën, de union van alle JSON-keys is exact `enabled,id,name`. Het geauthenticeerde `GET /api/settings` geeft nog steeds volledig `CategorySettings` inclusief `extraInstructions`. UI-smoke op login/feed/instellingen zonder regressies; het wegwerp-testaccount is opgeruimd (`DELETE /api/account/me` → 200).
- **Spec statisch gediffed** tegen `main`: `CategorySettings` byte-identiek, enig nieuw schema `SharedCategory`, geen dangling `$ref`s.

Alle 7 acceptatiecriteria zijn afgedekt en geverifieerd.

## Bewust niet gedaan

- `frontend-reader`, de geauthenticeerde settings-endpoints, `CategorySettings` zelf en de `permitAll`/nginx-config zijn ongemoeid gelaten — het endpoint blijft bewust publiek.
- Geen retro-actieve maatregel voor eerder gelekte `extraInstructions`; dit is puur een contractwijziging vooruit.

## Aandachtspunten voor de volgende stap (niet blokkerend)

1. **Voor SF-1996 (documentatie):** de nieuwe zin in `backend-functional-spec.md` noemt "de geauthenticeerde `/api/settings/categories`-endpoints", maar dat pad bestaat niet — de echte endpoints zijn `GET`/`PUT /api/settings`. Inhoudelijk klopt de bewering; alleen de padnaam moet worden gecorrigeerd. Dezelfde fout stond al in de refined story.
2. **Spec-detail:** `SharedCategory` staat in `openapi.yaml` op `required: [id, name]` met `default: true` op `enabled`, overgenomen uit het request-georiënteerde `CategorySettings`. Voor dit response-only schema zou `required: [id, name, enabled]` zonder `default` het contract preciezer beschrijven. Kan mee in een volgende spec-aanraking.

<!-- deploy-summary:start -->
Het openbare overzicht met categorieën deelt vanaf nu alleen nog de naam en of een categorie aanstaat. De persoonlijke aanwijzingen die je bij een categorie invult, blijven daarmee privé en zijn niet langer voor buitenstaanders op te vragen. In de app zelf verandert er niets aan hoe alles werkt of eruitziet.
<!-- deploy-summary:end -->
